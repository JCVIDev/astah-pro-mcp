package com.astahpromcp.tool.visualization;

import com.astahpromcp.tool.ToolDefinition;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.dot.ExeState;
import net.sourceforge.plantuml.dot.GraphvizRuntimeEnvironment;
import net.sourceforge.plantuml.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class PlantumlSandboxRenderingTest {
    
    private static final String MARKER = "SANDBOX_TEST_MARKER_5c1d";
    
    @TempDir
    Path dir;
    
    // The directory as PlantUML code writes a path
    private String dirPath;
    
    @BeforeEach
    void writeLocalFiles() throws Exception {
        Files.writeString(dir.resolve("included.puml"), "Alice -> Bob : " + MARKER + "\n");
        Files.writeString(dir.resolve("data.json"), "{ \"marker\": \"" + MARKER + "\" }\n");
        Files.writeString(dir.resolve("puml-theme-local.puml"), "!$themeMarker = \"" + MARKER + "\"\n");
        // Named after the marker, for the include whose path is built from what data.json holds
        Files.writeString(dir.resolve(MARKER + ".puml"), "Alice -> Bob : CHAINED_" + MARKER + "\n");
        ImageIO.write(new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB), "png", dir.resolve("image.png").toFile());
        
        dirPath = dir.toAbsolutePath().toString().replace('\\', '/');
    }
    
    @Test
    void render_ng_doesNotIncludeALocalFile() throws Exception {
        PlantumlTool.Rendering rendering = render("!include " + dirPath + "/included.puml\n");
        
        assertFalse(svgOf(rendering).contains(MARKER));
        assertTrue(rendering.reportsError());
    }
    
    @Test
    void render_ng_doesNotLoadALocalJsonFile() throws Exception {
        String svg = svgOf(render("!$j = %load_json(\"" + dirPath + "/data.json\")\nAlice -> Bob : $j.marker\n"));
        
        assertFalse(svg.contains(MARKER));
    }
    
    @Test
    void render_ng_doesNotTellWhetherALocalFileExists() throws Exception {
        String svg = svgOf(render("Alice -> Bob : FE=%file_exists(\"" + dirPath + "/included.puml\")\n"));
        
        assertTrue(svg.contains("FE=0"), svg);
        assertFalse(svg.contains("FE=1"));
    }
    
    @Test
    void render_ng_doesNotEmbedALocalImage() throws Exception {
        String svg = svgOf(render("Alice -> Bob : image\nnote right: <img:" + dirPath + "/image.png>\n"));
        
        assertFalse(svg.contains("<image"), svg);
    }
    
    @Test
    void render_ng_doesNotApplyALocalTheme() throws Exception {
        String svg = svgOf(render("!theme local from " + dirPath + "\nAlice -> Bob : $themeMarker\n"));
        
        assertFalse(svg.contains(MARKER));
    }
    
    @Test
    void render_ng_doesNotIncludeAFileChosenByWhatAnotherFileHolds() throws Exception {
        String svg = svgOf(render(
                "!$j = %load_json(\"" + dirPath + "/data.json\")\n"
                + "!$f = \"" + dirPath + "/\" + $j.marker + \".puml\"\n"
                + "!include $f\n"));
        
        assertFalse(svg.contains(MARKER));
    }
    
    @Test
    void render_ng_doesNotFetchAUrlEvenWhenItIsAllowlisted() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = ("Alice -> Bob : " + MARKER + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        
        // Outside the sandbox, an allowlisted URL is fetched even on loopback, which PlantUML otherwise refuses; the sandbox refuses it all the same
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        String originalAllowlist = System.getProperty(SecurityUtils.ALLOWLIST_URL);
        System.setProperty(SecurityUtils.ALLOWLIST_URL, base);
        try {
            for (String directive : new String[] {"!include " + base + "a.puml", "!includeurl " + base + "b.puml"}) {
                String svg = svgOf(render(directive + "\n"));
                
                assertFalse(svg.contains(MARKER), directive);
            }
            assertEquals(0, hits.get());
            
        } finally {
            if (originalAllowlist == null) {
                System.clearProperty(SecurityUtils.ALLOWLIST_URL);
            } else {
                System.setProperty(SecurityUtils.ALLOWLIST_URL, originalAllowlist);
            }
            server.stop(0);
        }
    }
    
    @Test
    void render_ok_drawsTheStandardLibrary() throws Exception {
        PlantumlTool.Rendering rendering = render("!include <C4/C4_Container>\nContainer(app, \"C4_OK\", \"Java\")\n");
        
        assertTrue(svgOf(rendering).contains("C4_OK"));
        assertFalse(rendering.reportsError());
    }
    
    @Test
    void render_ok_drawsABuiltInTheme() throws Exception {
        PlantumlTool.Rendering rendering = render("!theme cerulean\nAlice -> Bob : THEME_OK\n");
        
        assertTrue(svgOf(rendering).contains("THEME_OK"));
        assertFalse(rendering.reportsError());
    }
    
    @Test
    void render_ok_drawsAClassDiagramWithGraphviz() throws Exception {
        File dot = GraphvizRuntimeEnvironment.getInstance().getDotExe();
        assumeTrue(dot != null && ExeState.checkFile(dot) == ExeState.OK, "Graphviz is not installed");
        
        PlantumlTool.Rendering rendering = render("class Foo\nclass Bar\nFoo --> Bar : CLASS_OK\n");
        
        assertTrue(svgOf(rendering).contains("CLASS_OK"));
        assertFalse(rendering.reportsError());
    }
    
    @Test
    void generateDiagramImageFromPlantuml_ok_explainsTheSandboxWithAnImageThatShowsAnError() {
        ToolDefinition definition = new PlantumlTool().createToolDefinitions().getFirst();
        
        McpSchema.CallToolResult result = definition.toolHandler().apply(null, new McpSchema.CallToolRequest(
                definition.toolSchema().name(),
                Map.of("plantumlCode", "@startuml\n!include " + dirPath + "/included.puml\n@enduml"),
                null));
        
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> result.content().toString());
        assertEquals(2, result.content().size());
        McpSchema.TextContent note = assertInstanceOf(McpSchema.TextContent.class, result.content().get(0));
        assertEquals(PlantumlTool.ERROR_NOTE, note.text());
        McpSchema.ImageContent image = assertInstanceOf(McpSchema.ImageContent.class, result.content().get(1));
        assertEquals("image/png", image.mimeType());
    }
    
    private static PlantumlTool.Rendering render(String body) throws Exception {
        return PlantumlTool.render("@startuml\n" + body + "@enduml\n", FileFormat.SVG);
    }
    
    private static String svgOf(PlantumlTool.Rendering rendering) {
        return new String(rendering.bytes(), StandardCharsets.UTF_8);
    }
}
