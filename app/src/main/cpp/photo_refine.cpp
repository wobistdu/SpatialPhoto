// Source-view color fitting. Geometry, opacity, ordering and Gaussian count are immutable.
// Kept independent of Android so the exact production solver can be tested on desktop.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>
struct Projected { float u,v,a,b,c,opacity,r,g,blue,z; };
struct Link { int pixel; float weight; };
std::vector<float> refine(const std::vector<Projected>& s,const std::vector<float>& target,int w,int h) {
    if(w<=0||h<=0||w>960||h>960||target.size()!=size_t(w*h*3)||s.size()>300000)throw std::runtime_error("Invalid refinement input");
    const int n=s.size();
    std::vector<int> order(n); for(int i=0;i<n;i++)order[i]=i;
    std::stable_sort(order.begin(),order.end(),[&](int a,int b){return s[a].z<s[b].z;});
    std::vector<float> trans(w*h,1),rgb(w*h*3),original(n*3),color(n*3),mass(n,0);
    std::vector<std::vector<Link>> links(n); size_t totalLinks=0;
    for(int i=0;i<n;i++) {color[i*3]=original[i*3]=s[i].r;color[i*3+1]=original[i*3+1]=s[i].g;color[i*3+2]=original[i*3+2]=s[i].blue;}
    for(auto id:order) {
        const auto&p=s[id]; const float det=p.a*p.c-p.b*p.b;
        if(!(det>0)||!std::isfinite(det)||!(p.z>0))continue;
        const float ia=p.c/det,ib=-p.b/det,ic=p.a/det,rx=3*std::sqrt(p.a),ry=3*std::sqrt(p.c);
        // Clamp floats before converting to int, including off-image ellipses.
        const int x0=int(std::clamp(std::floor(p.u-rx),0.f,float(w))),x1=int(std::clamp(std::ceil(p.u+rx),-1.f,float(w-1)));
        const int y0=int(std::clamp(std::floor(p.v-ry),0.f,float(h))),y1=int(std::clamp(std::ceil(p.v+ry),-1.f,float(h-1)));
        for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++) {
            const int i=y*w+x; if(trans[i]<.0001f)continue;
            const float dx=x-p.u,dy=y-p.v,q=ia*dx*dx+2*ib*dx*dy+ic*dy*dy;
            if(q>9)continue;
            const float alpha=std::min(.99f,p.opacity*std::exp(-.5f*q));if(alpha<1.f/255)continue;
            const float weight=alpha*trans[i]; trans[i]*=1-alpha;
            if(weight>1e-6f){if(++totalLinks>40000000)throw std::runtime_error("Photo refinement memory budget exceeded");links[id].push_back({i,weight});mass[id]+=weight;}
        }
    }
    // Twelve bounded, regularized Jacobi updates. Hidden points retain their original color.
    for(int step=0;step<12;step++) {
        std::fill(rgb.begin(),rgb.end(),0);
        for(int id=0;id<n;id++)for(const auto&l:links[id])for(int c=0;c<3;c++)rgb[l.pixel*3+c]+=l.weight*color[id*3+c];
        for(int id=0;id<n;id++) {
            if(mass[id]<.01f)continue; float grad[3]={};
            for(const auto&l:links[id])for(int c=0;c<3;c++)grad[c]+=l.weight*(target[l.pixel*3+c]-rgb[l.pixel*3+c]);
            for(int c=0;c<3;c++){const int k=id*3+c;const float delta=grad[c]/mass[id]-.02f*(color[k]-original[k]);color[k]=std::clamp(color[k]+delta,std::max(0.f,original[k]-.12f),std::min(1.f,original[k]+.12f));}
        }
    }
    return color;
}
std::vector<Projected> project(const float* rows,int n,const float* k) {
    std::vector<Projected> result(n);
    for(int i=0;i<n;i++) {
        const float* p=rows+i*14;for(int c=0;c<14;c++)if(!std::isfinite(p[c]))throw std::runtime_error("Non-finite Gaussian");
        if(p[2]<=0)throw std::runtime_error("Invalid Gaussian depth");
        double norm=0;for(int c=10;c<14;c++)norm+=double(p[c])*p[c];if(norm<1e-16)throw std::runtime_error("Invalid Gaussian rotation");norm=std::sqrt(norm);
        const double qw=p[10]/norm,qx=p[11]/norm,qy=p[12]/norm,qz=p[13]/norm;
        const double R[3][3]={{1-2*(qy*qy+qz*qz),2*(qx*qy-qw*qz),2*(qx*qz+qw*qy)}, {2*(qx*qy+qw*qz),1-2*(qx*qx+qz*qz),2*(qy*qz-qw*qx)}, {2*(qx*qz-qw*qy),2*(qy*qz+qw*qx),1-2*(qx*qx+qy*qy)}};
        const double J[2][3]={{k[0]/p[2],0,-double(k[0])*p[0]/(double(p[2])*p[2])},{0,k[1]/p[2],-double(k[1])*p[1]/(double(p[2])*p[2])}};
        double C[2][2]={};
        for(int axis=0;axis<3;axis++) {
            double a=0,b=0;for(int j=0;j<3;j++){a+=J[0][j]*R[j][axis];b+=J[1][j]*R[j][axis];}
            const double variance=std::exp(2.*p[7+axis]);C[0][0]+=a*a*variance;C[0][1]+=a*b*variance;C[1][1]+=b*b*variance;
        }
        auto&s=result[i];s={k[0]*p[0]/p[2]+k[2],k[1]*p[1]/p[2]+k[3],float(C[0][0]+.3),float(C[0][1]),float(C[1][1]+.3),float(1/(1+std::exp(-double(p[6])))),std::clamp(p[3]*.28209479177387814f+.5f,0.f,1.f),std::clamp(p[4]*.28209479177387814f+.5f,0.f,1.f),std::clamp(p[5]*.28209479177387814f+.5f,0.f,1.f),p[2]};
        if(!std::isfinite(s.u)||!std::isfinite(s.v)||!std::isfinite(s.a)||!std::isfinite(s.b)||!std::isfinite(s.c))throw std::runtime_error("Invalid projected Gaussian");
    }
    return result;
}
#ifdef __ANDROID__
#include <jni.h>
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_example_spatialphoto_data_repository_PhotoColorRefinement_fit(JNIEnv* env,jobject,jfloatArray source,jintArray pixels,jint w,jint h,jfloatArray calibration) {
    try {
        const int length=env->GetArrayLength(source);
        if(length%14||length<14||length>300000*14||w<=0||h<=0||w>960||h>960||env->GetArrayLength(pixels)!=w*h||env->GetArrayLength(calibration)!=4)throw std::runtime_error("Invalid refinement arrays");
        std::vector<float> rows(length),target(w*h*3);std::vector<jint> image(w*h);float k[4];
        env->GetFloatArrayRegion(source,0,length,rows.data());env->GetFloatArrayRegion(calibration,0,4,k);env->GetIntArrayRegion(pixels,0,w*h,image.data());
        if(env->ExceptionCheck())return nullptr;
        for(int c=0;c<4;c++)if(!std::isfinite(k[c])||(c<2&&k[c]<=0))throw std::runtime_error("Invalid calibration");
        for(int i=0;i<w*h;i++){target[i*3]=((image[i]>>16)&255)/255.f;target[i*3+1]=((image[i]>>8)&255)/255.f;target[i*3+2]=(image[i]&255)/255.f;}
        auto projected=project(rows.data(),length/14,k);auto colors=refine(projected,target,w,h);
        jfloatArray result=env->NewFloatArray(colors.size());if(result)env->SetFloatArrayRegion(result,0,colors.size(),colors.data());return result;
    } catch(const std::exception&e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());return nullptr;}
}
#endif
