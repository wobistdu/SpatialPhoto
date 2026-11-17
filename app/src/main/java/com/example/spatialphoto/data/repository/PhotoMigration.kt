package com.example.spatialphoto.data.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

private const val LEGACY_PACKAGE="com.example.sharpspatial.p8a41c6d9"
private const val AUTHORITY="$LEGACY_PACKAGE.photo-migration"
private fun File.digest():String=inputStream().use { input ->
    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(1024*1024)
    while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
    digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
}
/** Only the temporary, same-signature bridge enables this provider. Bytes stay on the headset. */
class PhotoMigrationProvider:ContentProvider() {
    override fun onCreate()=requireNotNull(context).packageName==LEGACY_PACKAGE
    private fun allowedFiles():List<File> {
        val root=requireNotNull(context).filesDir
        val photos=File(root,"captures").listFiles().orEmpty().filter{it.isDirectory && it.name.matches(Regex("photo-[0-9]+"))}.flatMap { dir ->
            dir.listFiles().orEmpty().filter{it.isFile && (it.name in setOf("photo.png","camera.json","scene.ply","scene.json","queued","reconstruction-error.txt") || it.name.matches(Regex("(color-v1|window-photo-v8)-[a-zA-Z0-9-]+\\.ply")))}
        }
        return photos+File(root,"models").listFiles().orEmpty().filter{it.isFile && it.name in setOf("sharp-hd.onnx","sharp-hd.onnx.data","sharp-fp16.onnx","sharp-fp16.onnx.data")}
    }
    private fun checkCaller() {
        check(requireNotNull(context).packageName==LEGACY_PACKAGE)
        require(requireNotNull(context).packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().contains("com.example.spatialphoto")){"Only SpatialPhoto can migrate"}
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        checkCaller();require(uri.path=="/manifest")
        val root=requireNotNull(context).filesDir
        return MatrixCursor(arrayOf("path","bytes","sha256")).apply{allowedFiles().forEach{addRow(arrayOf<Any>(it.relativeTo(root).invariantSeparatorsPath,it.length(),it.digest()))}}
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        checkCaller();require(mode=="r")
        val root=requireNotNull(context).filesDir
        val file=File(root,requireNotNull(uri.getQueryParameter("path"))).canonicalFile
        require(file in allowedFiles().map{it.canonicalFile})
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri:Uri)="application/octet-stream"
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
}

class MigratingPhotoLibraryRepository(private val context:Context,private val delegate:PhotoLibraryRepository):PhotoLibraryRepository by delegate {
    override suspend fun list()=withContext(Dispatchers.IO) {
        val marker=File(context.filesDir,"migration-v12.json")
        if(context.packageName=="com.example.spatialphoto" && !marker.isFile && context.packageManager.resolveContentProvider(AUTHORITY,0)!=null) {
            data class Entry(val path:String,val bytes:Long,val sha:String)
            val entries=requireNotNull(context.contentResolver.query(Uri.parse("content://$AUTHORITY/manifest"),null,null,null,null)){"旧版照片暂时不可用，请重新打开应用重试"}.use { cursor ->
                buildList{while(cursor.moveToNext())add(Entry(cursor.getString(0),cursor.getLong(1),cursor.getString(2)))}
            }
            val groups=entries.groupBy{if(it.path.startsWith("captures/"))it.path.substringBeforeLast('/') else "models"}
            var verified=0
            for((group,files) in groups) {
                require(group=="models" || group.matches(Regex("captures/photo-[0-9]+")))
                val destination=File(context.filesDir,group)
                if(group!="models" && destination.exists())continue
                val staging=if(group=="models")destination else File(context.filesDir,"migration-pending/${group.substringAfterLast('/')}")
                check(staging.isDirectory || staging.mkdirs())
                for(entry in files) {
                    val target=File(staging,entry.path.substringAfterLast('/'))
                    if(target.isFile && target.length()==entry.bytes && target.digest()==entry.sha){verified++;continue}
                    val pending=File(staging,target.name+".migrating")
                    val uri=Uri.parse("content://$AUTHORITY/file").buildUpon().appendQueryParameter("path",entry.path).build()
                    context.contentResolver.openInputStream(uri)!!.use { input->pending.outputStream().use{input.copyTo(it,1024*1024)} }
                    check(pending.length()==entry.bytes && pending.digest()==entry.sha){"照片迁移校验失败"}
                    check(pending.renameTo(target));verified++
                }
                if(group!="models") {destination.parentFile!!.mkdirs();check(staging.renameTo(destination))}
            }
            marker.writeText(JSONObject().put("success",true).put("sourceFiles",entries.size).put("verifiedFiles",verified).put("photos",groups.keys.count{it.startsWith("captures/")}).put("deviceLocalOnly",true).toString(2))
        }
        delegate.list()
    }
}
