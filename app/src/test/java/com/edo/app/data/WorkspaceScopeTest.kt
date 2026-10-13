package com.edo.app.data

import com.edo.app.agent.FileWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceScopeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tree(docId: String) =
        "content://com.android.externalstorage.documents/tree/" + java.net.URLEncoder.encode(docId, "UTF-8")

    private fun project(id: Long, uri: String) = ProjectEntity(id = id, name = "p$id", workspaceUri = uri)

    @Test
    fun parsesTreeDocumentId() {
        assertEquals("primary:Edo/Workout Tracker", WorkspaceScope.treeDocumentId(tree("primary:Edo/Workout Tracker")))
        assertNull(WorkspaceScope.treeDocumentId("/sdcard/Edo/Pam"))
    }

    @Test
    fun siblingsDoNotOverlap() {
        assertFalse(WorkspaceScope.overlaps(tree("primary:Edo/Bubbly"), tree("primary:Edo/Bubbly2")))
        assertFalse(WorkspaceScope.overlaps(tree("primary:Edo/Pam"), tree("primary:Edo/Ola")))
    }

    @Test
    fun parentChildAndIdenticalOverlap() {
        assertTrue(WorkspaceScope.overlaps(tree("primary:Edo"), tree("primary:Edo/Pam")))
        assertTrue(WorkspaceScope.overlaps(tree("primary:Edo/Pam"), tree("primary:Edo")))
        assertTrue(WorkspaceScope.overlaps(tree("primary:Edo/Pam"), tree("primary:Edo/Pam")))
    }

    @Test
    fun conflictFindsOverlappingProject() {
        val others = listOf(project(1, tree("primary:Edo/Pam")), project(2, tree("primary:Edo/Ola")))
        assertEquals(1L, WorkspaceScope.conflict(tree("primary:Edo"), others)?.id)
        assertNull(WorkspaceScope.conflict(tree("primary:Edo/Bubbly"), others))
    }

    @Test
    fun safeSegmentsRejectsTraversal() {
        assertEquals(listOf("a", "b.txt"), WorkspaceScope.safeSegments("/a//b.txt"))
        assertNull(WorkspaceScope.safeSegments("../other/x"))
        assertNull(WorkspaceScope.safeSegments("a/./b"))
        assertNull(WorkspaceScope.safeSegments("a/../../b"))
    }

    @Test
    fun fileWorkspaceStaysInsideRootAndRejectsSiblingPrefix() {
        val base = tmp.newFolder("Edo")
        val root = java.io.File(base, "Bubbly").apply { mkdirs() }
        val sibling = java.io.File(base, "Bubbly-secret").apply { mkdirs() }
        java.io.File(sibling, "key.txt").writeText("secret")
        java.io.File(root, "ok.txt").writeText("fine")

        val ws = FileWorkspace(root)
        assertEquals("fine", ws.read("ok.txt"))
        assertNull(ws.read("../Bubbly-secret/key.txt"))
        assertFalse(ws.write("../Bubbly-secret/new.txt", "x"))
        assertFalse(java.io.File(sibling, "new.txt").exists())
        assertNotNull(ws.ls(""))
    }
}
