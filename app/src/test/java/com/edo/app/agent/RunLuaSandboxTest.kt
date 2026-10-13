package com.edo.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * run_lua is always available, but must not be a way around the workspace boundary: no Java bridge,
 * no os/io, file access confined to its own project, and network only when opted in.
 */
class RunLuaSandboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeFetcher(private val response: String = "HTTP 200\nhi") : HttpFetcher {
        val calls = mutableListOf<List<String?>>()
        override suspend fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): String {
            calls += listOf(method, url, body, headers.entries.joinToString(",") { "${it.key}=${it.value}" })
            return response
        }
    }

    private suspend fun lua(
        code: String,
        ws: Workspace = InMemoryWorkspace(),
        fetcher: HttpFetcher? = null,
        allowNetwork: Boolean = false,
    ): ToolResult {
        val json = """{"code":"${code.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}"}"""
        return RunLuaTool(ws, fetcher, allowNetwork).invoke(json)
    }

    @Test
    fun ordinaryProgramsStillWork() = runTest {
        val r = lua("local t = {} for i = 1, 3 do t[#t+1] = math.floor(i * 1.5) end print(table.concat(t, ','), string.upper('ok'))")
        assertFalse(r.content, r.isError)
        assertEquals("1,3,4\tOK", r.content)
    }

    @Test
    fun dangerousGlobalsAreAbsent() = runTest {
        val names = listOf("luajava", "io", "os", "debug", "package", "require", "dofile", "loadfile")
        val r = lua("for _, n in ipairs({" + names.joinToString(",") { "'$it'" } + "}) do print(n, type(_G[n])) end")
        assertFalse(r.content, r.isError)
        for (line in r.content.lines()) {
            val (name, type) = line.split('\t')
            assertEquals("$name should be removed", "nil", type)
        }
    }

    @Test
    fun cannotReachJavaClassesToTouchTheFilesystem() = runTest {
        val r = lua(
            "local File = luajava.bindClass('java.io.File')\n" +
                "local f = luajava.new(File, '/')\n" +
                "print(f:exists())",
            allowNetwork = true,
            fetcher = FakeFetcher(),
        )
        assertTrue("lua reached java.io.File: ${r.content}", r.isError)
    }

    @Test
    fun readsItsOwnProjectFilesByDefault() = runTest {
        val ws = InMemoryWorkspace(mapOf("notes/a.txt" to "hello", "b.txt" to "x"))
        val r = lua(
            "print(fs.read('notes/a.txt'))\n" +
                "print(fs.exists('b.txt'), fs.exists('missing.txt'))\n" +
                "local names = {} for _, e in ipairs(fs.ls('')) do names[#names+1] = e.name end table.sort(names) print(table.concat(names, ','))",
            ws,
        )
        assertFalse(r.content, r.isError)
        assertEquals("hello\ntrue\tfalse\nb.txt,notes", r.content)
    }

    @Test
    fun cannotReadOutsideItsRootOrSiblingProjects() = runTest {
        val base = tmp.newFolder("Edo")
        val root = java.io.File(base, "Ola").apply { mkdirs() }
        val other = java.io.File(base, "Ola-other").apply { mkdirs() }
        java.io.File(other, "key.txt").writeText("secret")
        java.io.File(root, "ok.txt").writeText("fine")
        val ws = FileWorkspace(root)
        val r = lua(
            "print(fs.read('ok.txt'))\n" +
                "print(fs.read('../Ola-other/key.txt'))\n" +
                "print(fs.exists('../Ola-other'))\n" +
                "print(fs.ls('../Ola-other'))",
            ws,
        )
        assertFalse(r.content, r.isError)
        assertEquals("fine\nnil\nfalse\nnil", r.content)
    }

    @Test
    fun writesAndDeletesWorkByDefaultInsideTheProject() = runTest {
        val ws = InMemoryWorkspace(mapOf("old.txt" to "x"))
        val r = lua("print(fs.write('out/new.txt', 'data'))\nprint(fs.delete('old.txt'))", ws)
        assertFalse(r.content, r.isError)
        assertEquals("true\ntrue", r.content)
        assertEquals("data", ws.read("out/new.txt"))
        assertNull(ws.read("old.txt"))
    }

    @Test
    fun cannotWriteOrDeleteOutsideItsRoot() = runTest {
        val base = tmp.newFolder("Edo")
        val root = java.io.File(base, "Ola").apply { mkdirs() }
        val other = java.io.File(base, "Ola-other").apply { mkdirs() }
        java.io.File(other, "key.txt").writeText("secret")
        val r = lua(
            "print(fs.write('../Ola-other/new.txt', 'x'))\nprint(fs.delete('../Ola-other/key.txt'))",
            FileWorkspace(root),
        )
        assertFalse(r.content, r.isError)
        assertEquals("false\nfalse", r.content)
        assertFalse(java.io.File(other, "new.txt").exists())
        assertTrue(java.io.File(other, "key.txt").exists())
    }

    @Test
    fun networkIsOffByDefault() = runTest {
        val fetcher = FakeFetcher()
        val r = lua("print(type(http))", InMemoryWorkspace(), fetcher)
        assertFalse(r.content, r.isError)
        assertEquals("nil", r.content)
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test
    fun networkWorksWhenEnabled() = runTest {
        val fetcher = FakeFetcher("HTTP 200\nhi")
        val r = lua(
            "print(http.get('https://example.com/a'))\n" +
                "print(http.request('put', 'https://example.com/b', 'body', {['X-Test']='1'}))",
            InMemoryWorkspace(),
            fetcher,
            allowNetwork = true,
        )
        assertFalse(r.content, r.isError)
        assertEquals(
            listOf(listOf("GET", "https://example.com/a", null, ""), listOf("PUT", "https://example.com/b", "body", "X-Test=1")),
            fetcher.calls,
        )
    }

    @Test
    fun descriptionReflectsWhatIsEnabled() {
        val off = RunLuaTool(InMemoryWorkspace()).spec.description
        val on = RunLuaTool(InMemoryWorkspace(), FakeFetcher(), allowNetwork = true).spec.description
        assertTrue(off.contains("no network") || off.contains("There is no network access"))
        assertFalse(off.contains("http.get"))
        assertTrue(on.contains("http.get"))
        assertTrue(off.contains("fs.write") && on.contains("fs.write"))
    }
}
