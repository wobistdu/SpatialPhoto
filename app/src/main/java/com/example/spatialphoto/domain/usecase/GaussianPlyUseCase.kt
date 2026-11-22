package com.example.spatialphoto.domain.usecase

import com.example.spatialphoto.domain.model.*
import java.io.File
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Convert SHARP NDC covariance to camera coordinates, then encode canonical degree-0 PLY. */
object GaussianPlyUseCase {
    fun validate(g: GaussianOutput) {
        require(g.count in 1..300000) { "Gaussian count outside supported model outputs" }
        require(g.means.size==g.count*3 && g.scales.size==g.count*3 && g.rotations.size==g.count*4 && g.colors.size==g.count*3)
        require(listOf(g.means,g.scales,g.rotations,g.colors,g.opacities).all { a -> a.all { it.isFinite() } }) { "Gaussian contains NaN/Inf" }
        require(g.scales.all { it>0f } && g.opacities.all { it in 0f..1f }) { "Invalid scale/opacity" }
    }
    fun write(g: GaussianOutput, photo: CapturePhoto, file: File): Int {
        validate(g)
        require(photo.fx>0 && photo.fy>0)
        val ax=photo.width/(2.0*photo.fx);val ay=photo.height/(2.0*photo.fy)
        val ox=(photo.width/2.0-photo.cx)/photo.fx;val oy=(photo.height/2.0-photo.cy)/photo.fy
        val a=arrayOf(doubleArrayOf(ax,0.0,ox),doubleArrayOf(0.0,ay,oy),doubleArrayOf(0.0,0.0,1.0))
        val cameraSpace=g.source==GaussianSource.DEPTH_SMALL_CAMERA
        fun nx(i:Int):Float = if(cameraSpace)((g.means[i*3]/g.means[i*3+2]-ox)/ax).toFloat() else g.means[i*3]/g.means[i*3+2]
        fun ny(i:Int):Float = if(cameraSpace)((g.means[i*3+1]/g.means[i*3+2]-oy)/ay).toFloat() else g.means[i*3+1]/g.means[i*3+2]
        var keep=(0 until g.count).filter { i -> g.means[i*3+2]>0.03f && g.means[i*3+2]<100f && g.opacities[i]>.005f &&
            (if(cameraSpace)abs(nx(i))<1f && abs(ny(i))<1f else abs(g.means[i*3])<g.means[i*3+2] && abs(g.means[i*3+1])<g.means[i*3+2]) }
        if(keep.size>GaussianBudget.MAX_COUNT) {
            val n=keep.size
            val selected=GaussianBudget.select(FloatArray(n){if(cameraSpace).5f+.5f*nx(keep[it]) else .5f+.5f*g.means[keep[it]*3]/g.means[keep[it]*3+2]},
                FloatArray(n){if(cameraSpace).5f+.5f*ny(keep[it]) else .5f+.5f*g.means[keep[it]*3+1]/g.means[keep[it]*3+2]},
                FloatArray(n){g.means[keep[it]*3+2]},FloatArray(n){g.opacities[keep[it]]})
            keep=selected.map{keep[it]}
        }
        require(keep.size>100) { "Too few valid Gaussians" }
        val provenance=if(cameraSpace)"DepthAnythingV2_Small_relative_depth" else "SHARP${g.inputResolution}_FP16_research_derivative"
        val header="ply\nformat binary_little_endian 1.0\ncomment $provenance\nelement vertex ${keep.size}\n"+
            listOf("x","y","z","f_dc_0","f_dc_1","f_dc_2","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3").joinToString("") { "property float $it\n" }+"end_header\n"
        val partial=File(file.parentFile,"${file.name}.partial")
        BufferedOutputStream(partial.outputStream(),256*1024).use { out ->
            out.write(header.toByteArray(Charsets.US_ASCII))
            val row=ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            val workspace = CovarianceWorkspace()
            for(i in keep) {
                val m=i*3;val z=g.means[m+2]
                if(cameraSpace) {
                    for(c in 0..2)workspace.scales[c]=g.scales[m+c].toDouble()
                    for(c in 0..3)workspace.quat[c]=g.rotations[i*4+c].toDouble()
                } else workspace.transform(g.rotations, i*4, g.scales, m, a)
                val s=workspace.scales; val rot=workspace.quat
                row.clear()
                row.putFloat(if(cameraSpace)g.means[m] else (ax*g.means[m]+ox*z).toFloat());row.putFloat(if(cameraSpace)g.means[m+1] else (ay*g.means[m+1]+oy*z).toFloat());row.putFloat(z)
                for(c in 0..2) { val l=g.colors[m+c].coerceIn(0f,1f).toDouble();val sr=if(l<=.0031308)12.92*l else 1.055*l.pow(1/2.4)-.055;row.putFloat(((sr-.5)/.28209479177387814).toFloat()) }
                val op=g.opacities[i].coerceIn(1e-6f,1f-1e-6f);row.putFloat(ln(op/(1-op)))
                s.forEach { row.putFloat(ln(it.coerceAtLeast(1e-8)).toFloat()) }
                rot.forEach { row.putFloat(it.toFloat()) }
                out.write(row.array())
            }
        }
        check(partial.renameTo(file)) { "PLY atomic save failed" }
        return keep.size
    }
    internal fun covariance(q:FloatArray,s:FloatArray):Array<DoubleArray> {
        val norm=sqrt(q.sumOf{it.toDouble()*it}); require(norm>1e-10)
        val w=q[0]/norm;val x=q[1]/norm;val y=q[2]/norm;val z=q[3]/norm
        val r=arrayOf(doubleArrayOf(1-2*(y*y+z*z),2*(x*y-w*z),2*(x*z+w*y)),doubleArrayOf(2*(x*y+w*z),1-2*(x*x+z*z),2*(y*z-w*x)),doubleArrayOf(2*(x*z-w*y),2*(y*z+w*x),1-2*(x*x+y*y)))
        return Array(3){i->DoubleArray(3){j->(0..2).sumOf{k->r[i][k]*r[j][k]*s[k]*s[k]}}}
    }
    /** Symmetric 3x3 Jacobi eigenvectors. Orientation sign is corrected to determinant +1. */
    internal fun eigen(matrix:Array<DoubleArray>):Pair<DoubleArray,DoubleArray> {
        val c=Array(3){matrix[it].clone()};val v=Array(3){i->DoubleArray(3){j->if(i==j)1.0 else 0.0}}
        repeat(18) {
            var p=0;var q=1;for(i in 0..2)for(j in i+1..2)if(abs(c[i][j])>abs(c[p][q])){p=i;q=j}
            if(abs(c[p][q])>1e-14) {
                val angle=.5*atan2(2*c[p][q],c[q][q]-c[p][p]);val co=cos(angle);val si=sin(angle)
                val pp=c[p][p];val qq=c[q][q];val pq=c[p][q]
                c[p][p]=co*co*pp-2*co*si*pq+si*si*qq;c[q][q]=si*si*pp+2*co*si*pq+co*co*qq;c[p][q]=0.0;c[q][p]=0.0
                for(k in 0..2) { if(k!=p&&k!=q){val kp=c[k][p];val kq=c[k][q];c[k][p]=co*kp-si*kq;c[p][k]=c[k][p];c[k][q]=si*kp+co*kq;c[q][k]=c[k][q]};val vp=v[k][p];val vq=v[k][q];v[k][p]=co*vp-si*vq;v[k][q]=si*vp+co*vq }
            }
        }
        val det=v[0][0]*(v[1][1]*v[2][2]-v[1][2]*v[2][1])-v[0][1]*(v[1][0]*v[2][2]-v[1][2]*v[2][0])+v[0][2]*(v[1][0]*v[2][1]-v[1][1]*v[2][0])
        if(det<0)for(k in 0..2)v[k][2] = -v[k][2]
        val trace=v[0][0]+v[1][1]+v[2][2]
        val quat=DoubleArray(4)
        if(trace>0) {
            val t=sqrt(trace+1)*2;quat[0]=.25*t;quat[1]=(v[2][1]-v[1][2])/t;quat[2]=(v[0][2]-v[2][0])/t;quat[3]=(v[1][0]-v[0][1])/t
        } else if(v[0][0]>v[1][1] && v[0][0]>v[2][2]) {
            val t=sqrt(1+v[0][0]-v[1][1]-v[2][2])*2;quat[0]=(v[2][1]-v[1][2])/t;quat[1]=.25*t;quat[2]=(v[0][1]+v[1][0])/t;quat[3]=(v[0][2]+v[2][0])/t
        } else if(v[1][1]>v[2][2]) {
            val t=sqrt(1+v[1][1]-v[0][0]-v[2][2])*2;quat[0]=(v[0][2]-v[2][0])/t;quat[1]=(v[0][1]+v[1][0])/t;quat[2]=.25*t;quat[3]=(v[1][2]+v[2][1])/t
        } else {
            val t=sqrt(1+v[2][2]-v[0][0]-v[1][1])*2;quat[0]=(v[1][0]-v[0][1])/t;quat[1]=(v[0][2]+v[2][0])/t;quat[2]=(v[1][2]+v[2][1])/t;quat[3]=.25*t
        }
        val norm=sqrt(quat.sumOf{it*it});require(norm>1e-8);for(k in 0..3)quat[k]/=norm
        return DoubleArray(3){sqrt(max(c[it][it],1e-16))} to quat
    }
    /** Per-write workspace: no per-Gaussian matrix, quaternion, or Pair allocations. */
    internal class CovarianceWorkspace {
        val r=Array(3){DoubleArray(3)}
        val cov=Array(3){DoubleArray(3)}
        val c=Array(3){DoubleArray(3)}
        val v=Array(3){DoubleArray(3)}
        val scales=DoubleArray(3)
        val quat=DoubleArray(4)
        fun transform(q:FloatArray,qo:Int,s:FloatArray,so:Int,a:Array<DoubleArray>) {
        var squared=0.0; for(k in 0..3) squared += q[qo+k].toDouble()*q[qo+k]
            val norm=sqrt(squared); require(norm>1e-10)
        val w=q[qo+0]/norm;val x=q[qo+1]/norm;val y=q[qo+2]/norm;val z=q[qo+3]/norm
            r[0][0]=1-2*(y*y+z*z)
            r[0][1]=2*(x*y-w*z)
            r[0][2]=2*(x*z+w*y)
            r[1][0]=2*(x*y+w*z)
            r[1][1]=1-2*(x*x+z*z)
            r[1][2]=2*(y*z-w*x)
            r[2][0]=2*(x*z-w*y)
            r[2][1]=2*(y*z+w*x)
            r[2][2]=1-2*(x*x+y*y)
            for(i in 0..2) for(j in 0..2) {
                var sum=0.0
                for(k in 0..2) sum += r[i][k]*r[j][k]*s[so+k]*s[so+k]
                cov[i][j]=sum
            }
            for(i in 0..2) for(j in 0..2) {
                var sum=0.0
                for(u in 0..2) {
                    var inner=0.0
                    for(v in 0..2) inner += a[i][u]*cov[u][v]*a[j][v]
                    sum += inner
                }
                c[i][j]=sum
                v[i][j]=if(i==j)1.0 else 0.0
            }
        repeat(18) {
            var p=0;var q=1;for(i in 0..2)for(j in i+1..2)if(abs(c[i][j])>abs(c[p][q])){p=i;q=j}
            if(abs(c[p][q])>1e-14) {
                val angle=.5*atan2(2*c[p][q],c[q][q]-c[p][p]);val co=cos(angle);val si=sin(angle)
                val pp=c[p][p];val qq=c[q][q];val pq=c[p][q]
                c[p][p]=co*co*pp-2*co*si*pq+si*si*qq;c[q][q]=si*si*pp+2*co*si*pq+co*co*qq;c[p][q]=0.0;c[q][p]=0.0
                for(k in 0..2) { if(k!=p&&k!=q){val kp=c[k][p];val kq=c[k][q];c[k][p]=co*kp-si*kq;c[p][k]=c[k][p];c[k][q]=si*kp+co*kq;c[q][k]=c[k][q]};val vp=v[k][p];val vq=v[k][q];v[k][p]=co*vp-si*vq;v[k][q]=si*vp+co*vq }
            }
        }
        val det=v[0][0]*(v[1][1]*v[2][2]-v[1][2]*v[2][1])-v[0][1]*(v[1][0]*v[2][2]-v[1][2]*v[2][0])+v[0][2]*(v[1][0]*v[2][1]-v[1][1]*v[2][0])
        if(det<0)for(k in 0..2)v[k][2] = -v[k][2]
        val trace=v[0][0]+v[1][1]+v[2][2]
        if(trace>0) {
            val t=sqrt(trace+1)*2;quat[0]=.25*t;quat[1]=(v[2][1]-v[1][2])/t;quat[2]=(v[0][2]-v[2][0])/t;quat[3]=(v[1][0]-v[0][1])/t
        } else if(v[0][0]>v[1][1] && v[0][0]>v[2][2]) {
            val t=sqrt(1+v[0][0]-v[1][1]-v[2][2])*2;quat[0]=(v[2][1]-v[1][2])/t;quat[1]=.25*t;quat[2]=(v[0][1]+v[1][0])/t;quat[3]=(v[0][2]+v[2][0])/t
        } else if(v[1][1]>v[2][2]) {
            val t=sqrt(1+v[1][1]-v[0][0]-v[2][2])*2;quat[0]=(v[0][2]-v[2][0])/t;quat[1]=(v[0][1]+v[1][0])/t;quat[2]=.25*t;quat[3]=(v[1][2]+v[2][1])/t
        } else {
            val t=sqrt(1+v[2][2]-v[0][0]-v[1][1])*2;quat[0]=(v[1][0]-v[0][1])/t;quat[1]=(v[0][2]+v[2][0])/t;quat[2]=(v[1][2]+v[2][1])/t;quat[3]=.25*t
        }
        val qnorm=sqrt(quat.sumOf{it*it});require(qnorm>1e-8);for(k in 0..3)quat[k]/=qnorm
            for(k in 0..2) scales[k]=sqrt(max(c[k][k],1e-16))
        }
    }
}
