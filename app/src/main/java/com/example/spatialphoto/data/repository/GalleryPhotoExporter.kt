package com.example.spatialphoto.data.repository

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract as Documents
import com.example.spatialphoto.domain.model.PhotoItem
import com.example.spatialphoto.domain.usecase.PhotoExporter
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only the explicitly granted DCIM/Pictures tree is writable; no all-files permission. */
class GalleryPhotoExporter(private val context:Context):PhotoExporter {
    private val resolver=context.contentResolver
    private val prefs=context.getSharedPreferences("gallery-export",Context.MODE_PRIVATE)
    private fun destination()=prefs.getString("tree",null)?.let(Uri::parse)
    override fun hasDestination():Boolean {
        val tree=destination() ?: return false
        return resolver.persistedUriPermissions.any{it.uri==tree && it.isReadPermission && it.isWritePermission} && runCatching {
            val root=Documents.buildDocumentUriUsingTree(tree,Documents.getTreeDocumentId(tree))
            resolver.query(root,arrayOf(Documents.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use{it.moveToFirst()}==true
        }.getOrDefault(false)
    }
    fun remember(tree:Uri,flags:Int) {
        require(tree.authority=="com.android.externalstorage.documents"){"请选择内部存储中的 DCIM 或 Pictures 相册目录"}
        val id=Documents.getTreeDocumentId(tree)
        val relative=id.substringAfter(':',"")
        require(id.startsWith("primary:") && (relative=="DCIM" || relative.startsWith("DCIM/") || relative=="Pictures" || relative.startsWith("Pictures/"))){"请选择 DCIM 或 Pictures 相册目录"}
        resolver.takePersistableUriPermission(tree,flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        require(resolver.persistedUriPermissions.any{it.uri==tree && it.isReadPermission && it.isWritePermission}){"目录尚未授权读写"}
        prefs.edit().putString("tree",tree.toString()).apply()
    }
    private fun children(tree:Uri,parent:Uri):Map<String,Uri> {
        val rows=linkedMapOf<String,Uri>()
        val child=Documents.buildChildDocumentsUriUsingTree(tree,Documents.getDocumentId(parent))
        resolver.query(child,arrayOf(Documents.Document.COLUMN_DOCUMENT_ID,Documents.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{cursor->
            while(cursor.moveToNext())rows[cursor.getString(1)]=Documents.buildDocumentUriUsingTree(tree,cursor.getString(0))
        } ?: error("无法读取导出目录")
        return rows
    }
    override suspend fun export(photos:List<PhotoItem>,progress:(Int,Int)->Unit):String=withContext(Dispatchers.IO) {
        check(hasDestination()){"目录授权已失效，请重新授权"}
        val tree=checkNotNull(destination())
        val root=Documents.buildDocumentUriUsingTree(tree,Documents.getTreeDocumentId(tree))
        val folder=if(Documents.getTreeDocumentId(tree).substringAfterLast('/')=="SpatialPhoto")root else
            children(tree,root)["SpatialPhoto"] ?: Documents.createDocument(resolver,root,Documents.Document.MIME_TYPE_DIR,"SpatialPhoto") ?: error("无法创建 SpatialPhoto 相册")
        val existing=children(tree,folder).toMutableMap()
        data class Source(val file:File,val name:String,val mime:String)
        val sources=photos.flatMap{photo->
            val name=File(photo.photo.path).parentFile!!.name.replace(Regex("[^A-Za-z0-9_-]"),"_")
            buildList {
                add(Source(File(photo.photo.path),name,"image/png"))
                if(photo.ready) {
                    // Export camera-space Gaussians (Small has relative scale), not the window preview.
                    val refined=PhotoColorRefinement.prepare(File(photo.scene!!.path),photo.photo){ensureActive()}
                    add(Source(refined,name,"application/octet-stream"))
                }
            }
        }
        var written=0;var skipped=0
        progress(0,sources.size)
        for((index,source) in sources.withIndex()) {
            ensureActive()
            require(source.file.isFile && source.file.length()>0){"原文件不可用：${source.name}"}
            val digest=MessageDigest.getInstance("SHA-256")
            source.file.inputStream().buffered().use{input->val buf=ByteArray(131072);while(true){ensureActive();val n=input.read(buf);if(n<0)break;digest.update(buf,0,n)}}
            val hash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            val ext=if(source.mime=="image/png")"png" else "ply"
            val name="SpatialPhoto_${source.name}_${hash.take(12)}.$ext"
            val previous=existing[name]
            val same=previous!=null && runCatching {
                val check=MessageDigest.getInstance("SHA-256")
                resolver.openInputStream(previous)!!.buffered().use{input->val b=ByteArray(131072);while(true){val n=input.read(b);if(n<0)break;ensureActive();check.update(b,0,n)}}
                check.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==hash
            }.getOrDefault(false)
            if(same)skipped++ else {
                // A changed existing export belongs to the user. Keep it and save a fresh copy.
                val finalName=if(previous==null)name else "${name.substringBeforeLast('.')}_${UUID.randomUUID().toString().take(8)}.$ext"
                val partial=Documents.createDocument(resolver,folder,"application/octet-stream",".SpatialPhoto-${UUID.randomUUID()}.partial") ?: error("无法创建导出文件")
                var published=false
                try {
                    resolver.openOutputStream(partial,"wt")!!.buffered().use{out->source.file.inputStream().buffered().use{input->
                        val b=ByteArray(131072);while(true){ensureActive();val n=input.read(b);if(n<0)break;out.write(b,0,n)}
                    }}
                    ensureActive()
                    val result=Documents.renameDocument(resolver,partial,finalName) ?: error("导出文件保存失败")
                    published=true;existing[finalName]=result;written++
                    val id=Documents.getDocumentId(result)
                    if(id.startsWith("primary:"))MediaScannerConnection.scanFile(context,arrayOf("/storage/emulated/0/"+id.substringAfter(':')),arrayOf(source.mime),null)
                } finally {if(!published)runCatching{Documents.deleteDocument(resolver,partial)}}
            }
            progress(index+1,sources.size)
        }
        val pending=photos.count{!it.ready}
        "已导出 $written 个文件，$skipped 个已存在 · SpatialPhoto 相册" + if(pending>0)"；$pending 张 3D 尚未完成，完成后可再次导出" else ""
    }
}
