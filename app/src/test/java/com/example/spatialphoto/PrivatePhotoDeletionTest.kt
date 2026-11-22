package com.example.spatialphoto

import com.example.spatialphoto.data.repository.PrivatePhotoDeletion
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivatePhotoDeletionTest {
    @get:Rule val temp=TemporaryFolder()
    private fun photo(root:File,id:String):File {
        val dir=File(root,id).apply{mkdirs()}
        listOf("photo.png","camera.json","scene.ply","scene.json","reconstruction-error.txt").forEach{File(dir,it).writeText("test")}
        return File(dir,"photo.png")
    }
    @Test fun removesOneWholePhotoKeepsNeighbour() {
        val root=temp.newFolder("captures");val target=photo(root,"photo-1");val other=photo(root,"photo-2")
        PrivatePhotoDeletion.delete(root,target)
        assertFalse(target.parentFile!!.exists());assertTrue(other.isFile);assertEquals(listOf("photo-2"),root.listFiles()!!.map{it.name})
    }
    @Test fun bundledPhotoDeletionKeepsCapturedNeighbour() {
        val root=temp.newFolder("captures");val target=photo(root,"sample-guofeng-01-courtyard");val other=photo(root,"photo-2")
        PrivatePhotoDeletion.delete(root,target)
        assertFalse(target.parentFile!!.exists());assertTrue(other.isFile)
        PrivatePhotoDeletion.delete(root,target)
    }
    @Test fun bundledPhotoOutsideLibraryRejected() {
        val root=temp.newFolder("captures");val external=photo(temp.newFolder("outside"),"sample-guofeng-01-courtyard")
        try{PrivatePhotoDeletion.delete(root,external);fail("must reject")}catch(expected:IllegalArgumentException){}
        assertTrue(external.isFile)
    }
    @Test fun arbitraryDirectoryRejected() {
        val root=temp.newFolder("captures");val target=photo(root,"models")
        try{PrivatePhotoDeletion.delete(root,target);fail("must reject")}catch(expected:IllegalArgumentException){}
        assertTrue(target.isFile)
    }
    @Test fun repeatedDeleteIsIdempotent() {
        val root=temp.newFolder("captures");val target=photo(root,"photo-1")
        PrivatePhotoDeletion.delete(root,target);PrivatePhotoDeletion.delete(root,target);assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun externalDirectoryRejected() {
        val root=temp.newFolder("captures");val external=photo(temp.newFolder("outside"),"photo-1")
        try{PrivatePhotoDeletion.delete(root,external);fail("must reject")}catch(expected:IllegalArgumentException){}
        assertTrue(external.isFile)
    }
    @Test fun libraryRootCannotBeDeleted() {
        val root=temp.newFolder("captures");val image=File(root,"photo.png").apply{writeText("keep")}
        try{PrivatePhotoDeletion.delete(root,image);fail("must reject")}catch(expected:IllegalArgumentException){}
        assertTrue(image.isFile)
    }
    @Test fun photoDirectorySymlinkRejected() {
        val root=temp.newFolder("captures");val external=photo(temp.newFolder("outside"),"photo-1")
        val link=File(root,"photo-1");Files.createSymbolicLink(link.toPath(),external.parentFile!!.toPath())
        try{PrivatePhotoDeletion.delete(root,File(link,"photo.png"));fail("must reject")}catch(expected:IllegalArgumentException){}
        assertTrue(external.isFile)
    }
    @Test fun nestedLinkNotFollowed() {
        val root=temp.newFolder("captures");val target=photo(root,"photo-1");val external=temp.newFolder("outside")
        val keep=File(external,"keep.txt").apply{writeText("keep")}
        Files.createSymbolicLink(File(target.parentFile,"linked").toPath(),external.toPath())
        PrivatePhotoDeletion.delete(root,target);assertTrue(keep.isFile);assertFalse(target.parentFile!!.exists())
    }
}
