package com.example.spatialphoto.domain.usecase

import com.example.spatialphoto.domain.model.CapturePhoto
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

/** Deterministic coverage/depth-stratified thinning. Kept rows are never altered. */
object GaussianBudget {
    const val MAX_COUNT=250_000
    fun select(u:FloatArray,v:FloatArray,z:FloatArray,alpha:FloatArray,limit:Int=MAX_COUNT):IntArray {
        val n=u.size
        require(limit>0 && v.size==n && z.size==n && alpha.size==n)
        if(n<=limit)return IntArray(n){it}
        require((0 until n).all{u[it].isFinite() && v[it].isFinite() && z[it].isFinite() && z[it]>0 && alpha[it].isFinite()})
        val median=z.sorted()[n/2]
        val bins=Array(64*48*4){mutableListOf<Int>()}
        for(i in 0 until n) {
            val x=(u[i]*64).toInt().coerceIn(0,63);val y=(v[i]*48).toInt().coerceIn(0,47)
            val d=when {z[i]<median*.75f->0;z[i]<median->1;z[i]<median*1.5f->2;else->3}
            bins[(d*48+y)*64+x].add(i)
        }
        val occupied=bins.filter{it.isNotEmpty()}
        val reserve=if(limit>=occupied.size)1 else 0
        val remaining=limit-reserve*occupied.size
        val capacity=n-reserve*occupied.size
        val quotas=IntArray(occupied.size)
        val remainders=LongArray(occupied.size)
        for(i in occupied.indices) {
            val numerator=remaining.toLong()*(occupied[i].size-reserve)
            quotas[i]=reserve+(numerator/capacity).toInt();remainders[i]=numerator%capacity
        }
        var extras=limit-quotas.sum()
        for(i in occupied.indices.sortedWith(compareByDescending<Int>{remainders[it]}.thenBy{it})) {
            if(extras==0)break
            if(quotas[i]<occupied[i].size){quotas[i]++;extras--}
        }
        // Hash only breaks equal-opacity ties; it avoids scanline-shaped holes.
        fun tie(i:Int):Int {var x=i;x=(x xor (x ushr 16))*0x45d9f3b;x=(x xor (x ushr 16))*0x45d9f3b;return x xor (x ushr 16)}
        val result=IntArray(limit);var at=0
        occupied.forEachIndexed { k,indices->
            indices.sortWith(compareByDescending<Int>{alpha[it]}.thenBy{tie(it)}.thenBy{it})
            repeat(quotas[k]){result[at++]=indices[it]}
        }
        check(at==limit);result.sort();return result
    }
    /** Old captures use a derived budget cache; their original metric PLY remains intact. */
    fun prepare(source:File,photo:CapturePhoto,checkCancelled:()->Unit={}):File {
        checkCancelled()
        require(source.length() in 1..(300000L*56+4096))
        val bytes=source.readBytes();val marker="end_header\n".toByteArray()
        val start=(0 until minOf(4096,bytes.size-marker.size+1)).firstOrNull{i->marker.indices.all{bytes[i+it]==marker[it]}} ?: error("Invalid Gaussian header")
        val offset=start+marker.size;val header=bytes.copyOfRange(0,offset).toString(Charsets.US_ASCII)
        val fields=listOf("x","y","z","f_dc_0","f_dc_1","f_dc_2","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3")
        require(header.contains("format binary_little_endian 1.0") && header.lineSequence().filter{it.startsWith("property ")}.toList()==fields.map{"property float $it"})
        val n=header.lineSequence().first{it.startsWith("element vertex ")}.substringAfterLast(' ').toInt()
        require(n in 101..300000 && bytes.size==offset+n*56)
        if(n<=MAX_COUNT)return source
        val digest=MessageDigest.getInstance("SHA-256");digest.update(bytes)
        digest.update("budget-v2:$MAX_COUNT:${photo.width}:${photo.height}:${photo.fx}:${photo.fy}:${photo.cx}:${photo.cy}".toByteArray())
        val key=digest.digest().take(12).joinToString(""){"%02x".format(it.toInt() and 255)}
        val output=File(source.parentFile,"budget-v2-$key.ply")
        if(output.isFile && output.length()>4096)return output
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val u=FloatArray(n);val v=FloatArray(n);val z=FloatArray(n);val a=FloatArray(n)
        repeat(n){i->if(i%1024==0)checkCancelled();val pos=offset+i*56;z[i]=b.getFloat(pos+8);u[i]=(photo.fx*b.getFloat(pos)/z[i]+photo.cx)/photo.width;v[i]=(photo.fy*b.getFloat(pos+4)/z[i]+photo.cy)/photo.height;a[i]=b.getFloat(pos+24)}
        val visible=(0 until n).filter{u[it]>0f && u[it]<1f && v[it]>0f && v[it]<1f}
        require(visible.size>100){"Too few Gaussians inside the photograph"}
        val selected=select(FloatArray(visible.size){u[visible[it]]},FloatArray(visible.size){v[visible[it]]},
            FloatArray(visible.size){z[visible[it]]},FloatArray(visible.size){a[visible[it]]}).map{visible[it]}
        val outHeader=header.replace("element vertex $n","element vertex ${selected.size}").toByteArray(Charsets.US_ASCII)
        checkCancelled()
        val partial=File(output.parentFile,output.name+".partial-"+UUID.randomUUID())
        try {partial.outputStream().buffered().use{out->out.write(outHeader);selected.forEachIndexed{k,i->if(k%1024==0)checkCancelled();out.write(bytes,offset+i*56,56)}};checkCancelled();check(partial.renameTo(output))}finally{partial.delete()}
        return output
    }
}
