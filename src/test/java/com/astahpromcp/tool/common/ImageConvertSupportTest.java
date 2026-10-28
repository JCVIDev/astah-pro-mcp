package com.astahpromcp.tool.common;

import com.astahpromcp.config.McpServerConfig;
import com.astahpromcp.tool.common.ImageConvertSupport.FittedSvgImage;
import com.astahpromcp.tool.common.ImageConvertSupport.ParsedSvg;
import com.astahpromcp.tool.common.ImageConvertSupport.RasterizedSvgImage;
import com.sun.net.httpserver.HttpServer;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.util.ParsedURL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

public class ImageConvertSupportTest {
    
    private static final int RED = 0xFFFF0000;
    
    private final ImageConvertSupport imageConvertSupport = new ImageConvertSupport();
    
    @Test
    void parseSvg_ok_measuresTheNaturalSize() {
        ParsedSvg svg = imageConvertSupport.parseSvg(circleSvg(100, 80));
        
        assertEquals(100, svg.naturalWidth());
        assertEquals(80, svg.naturalHeight());
    }
    
    @Test
    void parseSvg_ok_resolvesPercentagesAgainstTheDefaultViewport() {
        // Batik sizes an SVG without absolute width and height against a 400x400 viewport; a measurement made with a
        // plain BridgeContext would report 1x1 for these instead.
        ParsedSvg viewBoxOnly = imageConvertSupport.parseSvg(
                "<svg viewBox=\"0 0 640 480\" xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"640\" height=\"480\"/></svg>");
        ParsedSvg percentages = imageConvertSupport.parseSvg(
                "<svg width=\"100%\" height=\"50%\" viewBox=\"0 0 200 100\" xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"200\" height=\"100\"/></svg>");
        
        assertEquals(400, viewBoxOnly.naturalWidth());
        assertEquals(400, viewBoxOnly.naturalHeight());
        assertEquals(400, percentages.naturalWidth());
        assertEquals(200, percentages.naturalHeight());
    }
    
    @Test
    void parseSvg_ok_measuresAnOversizedSvgWithoutAllocatingItsPixels() {
        long allocatedBefore = allocatedBytesOfThisThread();
        
        // 900 million pixels, about 3.4 GB as one ARGB image
        ParsedSvg svg = imageConvertSupport.parseSvg(circleSvg(30_000, 30_000));
        
        long allocated = allocatedBytesOfThisThread() - allocatedBefore;
        assertEquals(30_000, svg.naturalWidth());
        assertEquals(30_000, svg.naturalHeight());
        assertTrue(allocated < 64L * 1024 * 1024, "Measuring allocated " + allocated + " bytes");
    }
    
    @Test
    void parseSvg_ok_readsViewBoxMinXAndMinY() {
        Point2D.Double origin = imageConvertSupport.parseSvg(circleSvg(100, 80, "300 -40 100 80")).viewBoxOrigin();
        
        assertEquals(300.0, origin.getX());
        assertEquals(-40.0, origin.getY());
    }
    
    @Test
    void parseSvg_ok_acceptsCommaSeparatedViewBox() {
        Point2D.Double origin = imageConvertSupport.parseSvg(circleSvg(100, 80, "300, -40, 100, 80")).viewBoxOrigin();
        
        assertEquals(300.0, origin.getX());
        assertEquals(-40.0, origin.getY());
    }
    
    @Test
    void parseSvg_ok_fallsBackToOriginWithoutViewBox() {
        Point2D.Double origin = imageConvertSupport.parseSvg(circleSvg(100, 80)).viewBoxOrigin();
        
        assertEquals(0.0, origin.getX());
        assertEquals(0.0, origin.getY());
    }
    
    @Test
    void parseSvg_ng_rejectsMalformedViewBox() {
        // Batik refuses the viewBox while measuring, just as it used to when the SVG was first rasterized.
        assertThrows(IllegalArgumentException.class, () -> imageConvertSupport.parseSvg(circleSvg(100, 80, "300 -40")));
    }
    
    @Test
    void parseSvg_ng_rejectsBlankSvgCode() {
        assertThrows(IllegalArgumentException.class, () -> imageConvertSupport.parseSvg("  "));
    }
    
    @Test
    void parseSvg_ng_rejectsNonSvgMarkup() {
        assertThrows(IllegalArgumentException.class,
                () -> imageConvertSupport.parseSvg("<html xmlns=\"http://www.w3.org/1999/xhtml\"/>"));
    }
    
    @Test
    void parseSvg_ng_rejectsAnSvgWithNoArea() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> imageConvertSupport.parseSvg(circleSvg(0, 80)));
        
        assertTrue(e.getMessage().contains("nothing to draw"), e.getMessage());
    }
    
    @Test
    void requireInsertableSize_ok_acceptsTheLimitOnEachSide() {
        int limit = McpServerConfig.SVG_MAX_DISPLAY_DIMENSION;
        
        assertDoesNotThrow(() -> imageConvertSupport.requireInsertableSize(imageConvertSupport.parseSvg(circleSvg(limit, limit))));
    }
    
    @Test
    void requireInsertableSize_ng_rejectsASideAboveTheLimitWithTheWayToFixIt() {
        ParsedSvg svg = imageConvertSupport.parseSvg(circleSvg(5000, 40));
        
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> imageConvertSupport.requireInsertableSize(svg));
        
        assertTrue(e.getMessage().contains("5000x40"), e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(McpServerConfig.SVG_MAX_DISPLAY_DIMENSION)), e.getMessage());
        assertTrue(e.getMessage().contains("viewBox"), e.getMessage());
    }
    
    @Test
    void rasterize_ok_preservesSvgDisplaySize() {
        BufferedImage image = imageConvertSupport.rasterize(imageConvertSupport.parseSvg(circleSvg(100, 80)), 100, 80);
        
        assertEquals(100, image.getWidth());
        assertEquals(80, image.getHeight());
    }
    
    @Test
    void rasterize_ok_rasterizesAtGivenSize() {
        BufferedImage image = imageConvertSupport.rasterize(imageConvertSupport.parseSvg(circleSvg(100, 80)), 160, 120);
        
        assertEquals(160, image.getWidth());
        assertEquals(120, image.getHeight());
    }
    
    @Test
    void rasterize_ok_drawsTheSameImageFromAParsedSvgUsedMoreThanOnce() {
        String svgCode = styledSvg();
        ParsedSvg reused = imageConvertSupport.parseSvg(svgCode);
        
        // Batik keeps state on the document between transcodings, so the second use must not differ from a fresh parse.
        BufferedImage enlargedFirst = imageConvertSupport.rasterize(reused, 1050, 650);
        BufferedImage naturalAfterwards = imageConvertSupport.rasterize(reused, 420, 260);
        
        ParsedSvg fresh = imageConvertSupport.parseSvg(svgCode);
        assertSamePixels(imageConvertSupport.rasterize(fresh, 420, 260), naturalAfterwards);
        assertSamePixels(imageConvertSupport.rasterize(imageConvertSupport.parseSvg(svgCode), 1050, 650), enlargedFirst);
    }
    
    @Test
    void rasterize_ng_rejectsZeroWidth() {
        ParsedSvg svg = imageConvertSupport.parseSvg(circleSvg(100, 80));
        
        assertThrows(IllegalArgumentException.class, () -> imageConvertSupport.rasterize(svg, 0, 120));
    }
    
    @Test
    void rasterize_ng_rejectsNegativeHeight() {
        ParsedSvg svg = imageConvertSupport.parseSvg(circleSvg(100, 80));
        
        assertThrows(IllegalArgumentException.class, () -> imageConvertSupport.rasterize(svg, 160, -1));
    }
    
    @Test
    void rasterize_ng_refusesAboveThePixelLimitBeforeAllocatingThePixels() {
        ParsedSvg svg = imageConvertSupport.parseSvg(circleSvg(100, 80));
        long allocatedBefore = allocatedBytesOfThisThread();
        
        // 400 million pixels, about 1.5 GB as one ARGB image
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> imageConvertSupport.rasterize(svg, 20_000, 20_000));
        
        long allocated = allocatedBytesOfThisThread() - allocatedBefore;
        assertTrue(e.getMessage().contains("above the limit"), e.getMessage());
        assertTrue(e.getMessage().contains("viewBox"), e.getMessage());
        assertTrue(allocated < 64L * 1024 * 1024, "Refusing allocated " + allocated + " bytes");
    }
    
    @Test
    void rasterizeScaled_ok_upscalesImageAndKeepsDisplaySize() {
        RasterizedSvgImage result = imageConvertSupport.rasterizeScaled(imageConvertSupport.parseSvg(circleSvg(100, 80)), 4.0);
        
        assertEquals(400, result.image().getWidth());
        assertEquals(320, result.image().getHeight());
        assertEquals(100, result.displayWidth());
        assertEquals(80, result.displayHeight());
    }
    
    @Test
    void rasterizeScaled_ok_stopsEnlargingAtTheMaximumDimension() {
        RasterizedSvgImage result = imageConvertSupport.rasterizeScaled(imageConvertSupport.parseSvg(circleSvg(2000, 1000)), 4.0);
        
        assertEquals(4096, result.image().getWidth());
        assertEquals(2048, result.image().getHeight());
    }
    
    @Test
    void rasterizeScaled_ok_keepsTheAspectRatioOfAnSvgSizedByItsViewBoxOnly() {
        // Natural size 400x400 with the 640x480 drawing letterboxed in it. Passing the width alone used to yield a
        // 1600x400 raster, which the inserted image then squeezed into its 400x400 display size.
        ParsedSvg svg = imageConvertSupport.parseSvg(
                "<svg viewBox=\"0 0 640 480\" xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"640\" height=\"480\" fill=\"blue\"/></svg>");
        
        BufferedImage image = imageConvertSupport.rasterizeScaled(svg, 4.0).image();
        
        assertEquals(1600, image.getWidth());
        assertEquals(1600, image.getHeight());
        assertEquals(0, alpha(image, 800, 100), "The letterbox above the drawing must stay empty");
        assertEquals(255, alpha(image, 800, 800), "The drawing must fill the middle");
        assertEquals(255, alpha(image, 5, 800), "The drawing must span the full width");
    }
    
    @Test
    void rasterizeWithinPixelLimit_ok_keepsTheNaturalSizeWithinTheLimit() {
        FittedSvgImage fitted = imageConvertSupport.rasterizeWithinPixelLimit(imageConvertSupport.parseSvg(circleSvg(300, 200)));
        
        assertEquals(1.0, fitted.scale());
        assertEquals(300, fitted.image().getWidth());
        assertEquals(200, fitted.image().getHeight());
    }
    
    @Test
    void rasterizeWithinPixelLimit_ok_shrinksUniformlyAboveTheLimit() {
        // 20 million pixels, above the limit of about 16.8 million
        FittedSvgImage fitted = imageConvertSupport.rasterizeWithinPixelLimit(imageConvertSupport.parseSvg(circleSvg(20_000, 1_000)));
        
        BufferedImage image = fitted.image();
        assertTrue(fitted.scale() < 1.0, "scale " + fitted.scale());
        assertTrue((long) image.getWidth() * image.getHeight() <= McpServerConfig.SVG_MAX_RASTER_PIXELS,
                image.getWidth() + "x" + image.getHeight());
        assertEquals(20.0, image.getWidth() / (double) image.getHeight(), 0.05);
    }
    
    // An SVG may come from an untrusted source through the MCP client, so drawing it must not read a local file
    @Test
    void rasterize_ng_drawsNoImageFromAFileUrl(@TempDir Path tempDir) throws Exception {
        Path png = writePng(tempDir, filledImage(RED));
        
        BufferedImage image = renderOrNull(svgWith("<image x=\"0\" y=\"0\" width=\"20\" height=\"20\" xlink:href=\"" + png.toUri() + "\"/>"));
        
        if (image != null) {
            assertNotEquals(RED, image.getRGB(10, 10));
        }
    }
    
    // Nor send a request anywhere, which would let it reach the hosts the user's machine can reach
    @Test
    void rasterize_ng_sendsNoRequestForAnExternalReference() throws Exception {
        try (RecordingServer server = new RecordingServer()) {
            List<String> references = List.of(
                    "<image width=\"10\" height=\"10\" xlink:href=\"" + server.url("image.png") + "\"/>",
                    "<use xlink:href=\"" + server.url("shapes.svg#shape") + "\"/>",
                    "<style>@import url(" + server.url("style.css") + ");</style><rect width=\"10\" height=\"10\"/>",
                    "<rect width=\"10\" height=\"10\" fill=\"url(" + server.url("paints.svg#paint") + ")\"/>",
                    "<filter id=\"f\"><feImage xlink:href=\"" + server.url("filter.png") + "\"/></filter><rect width=\"10\" height=\"10\" filter=\"url(#f)\"/>",
                    // A base URI on the server makes a relative reference point at it
                    "<g xml:base=\"" + server.url("") + "\"><image width=\"10\" height=\"10\" xlink:href=\"image.png\"/></g>");
                    
            for (String reference : references) {
                renderOrNull(svgWith(reference));
                
                assertEquals(0, server.requests(), reference);
            }
        }
    }
    
    // An external entity in the document type declaration is read by the XML parser, before Batik sees the SVG at all
    @Test
    void parseSvg_ng_expandsNoExternalEntity(@TempDir Path tempDir) throws Exception {
        Path secret = Files.writeString(tempDir.resolve("secret.txt"), "TOP-SECRET");
        
        ParsedSvg svg = parseOrNull("<!DOCTYPE svg [<!ENTITY secret SYSTEM \"" + secret.toUri() + "\">]>"
                + svgWith("<text x=\"0\" y=\"15\">&secret;</text>"));
                
        if (svg != null) {
            assertFalse(svg.document().getDocumentElement().getTextContent().contains("TOP-SECRET"));
        }
    }
    
    @Test
    void parseSvg_ng_loadsNoExternalDtd() throws Exception {
        try (RecordingServer server = new RecordingServer()) {
            parseOrNull("<!DOCTYPE svg SYSTEM \"" + server.url("external.dtd") + "\">" + svgWith("<rect width=\"10\" height=\"10\"/>"));
            parseOrNull("<!DOCTYPE svg [<!ENTITY % parameter SYSTEM \"" + server.url("parameter.dtd") + "\"> %parameter;]>"
                    + svgWith("<rect width=\"10\" height=\"10\"/>"));
                    
            assertEquals(0, server.requests());
        }
    }
    
    // An SVG is drawn as a still image: it still draws, but neither its onload handler nor its scripts run, and no script is fetched
    @Test
    void rasterize_ng_runsAndFetchesNoScript() throws Exception {
        try (RecordingServer server = new RecordingServer()) {
            String svgCode = "<svg width=\"20\" height=\"20\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                    + " onload=\"document.documentElement.setAttribute('data-onload', 'ran')\">"
                    + "<script type=\"text/ecmascript\"><![CDATA[document.documentElement.setAttribute('data-script', 'ran');]]></script>"
                    + "<script type=\"text/ecmascript\" xlink:href=\"" + server.url("script.js") + "\"/>"
                    + "<script type=\"application/java-archive\" xlink:href=\"" + server.url("script.jar") + "\"/>"
                    + "<rect width=\"20\" height=\"20\" fill=\"#ff0000\"/>"
                    + "</svg>";
                    
            ParsedSvg svg = imageConvertSupport.parseSvg(svgCode);
            BufferedImage image = imageConvertSupport.rasterize(svg, 20, 20);
            
            Element root = svg.document().getDocumentElement();
            assertEquals("", root.getAttribute("data-onload"));
            assertEquals("", root.getAttribute("data-script"));
            assertEquals(0, server.requests());
            assertEquals(RED, image.getRGB(10, 10));
        }
    }
    
    // The policy itself, checked directly because a script that the policy let run might still fail to run for some other reason.
    @Test
    void svgTranscoder_ng_loadsNothingButDataUrisAndRunsNoScript() throws Exception {
        ImageConvertSupport.SvgTranscoder transcoder = new ImageConvertSupport.SvgTranscoder(false);
        UserAgent userAgent = transcoder.userAgent();
        ParsedURL document = new ParsedURL("internal:svg");
        
        assertEquals(Boolean.FALSE, transcoder.getTranscodingHints().get(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD));
        
        List<String> scriptTypes = new ArrayList<>(List.of(SVGAbstractTranscoder.DEFAULT_ALLOWED_SCRIPT_TYPES.split(",\\s*")));
        scriptTypes.add("application/java-archive");
        for (String type : scriptTypes) {
            assertThrows(SecurityException.class, () -> userAgent.checkLoadScript(type, null, document), type);
            assertThrows(SecurityException.class, () -> userAgent.checkLoadScript(type, new ParsedURL("internal:script"), document), type);
        }
        
        for (String url : List.of("http://127.0.0.1/image.png", "https://example.com/image.png", "file:///C:/image.png", "internal:image.png")) {
            assertThrows(SecurityException.class, () -> userAgent.checkLoadExternalResource(new ParsedURL(url), document), url);
        }
        assertDoesNotThrow(() -> userAgent.checkLoadExternalResource(new ParsedURL("data:image/png;base64,AAAA"), document));
    }
    
    // What an SVG carries within itself still draws: an image embedded as a data URI, and a reference to one of its own elements.
    @Test
    void rasterize_ok_drawsAnSvgImageEmbeddedAsADataUri() {
        String embedded = svgWith("<rect width=\"20\" height=\"20\" fill=\"#ff0000\"/>");
        String dataUri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(embedded.getBytes(StandardCharsets.UTF_8));
        
        BufferedImage image = render(svgWith("<image x=\"0\" y=\"0\" width=\"20\" height=\"20\" xlink:href=\"" + dataUri + "\"/>"));
        
        assertEquals(RED, image.getRGB(10, 10));
    }
    
    @Test
    void rasterize_ok_drawsAReferenceToAnElementOfTheSameSvg() {
        BufferedImage image = render(svgWith("<defs><rect id=\"r\" width=\"20\" height=\"20\" fill=\"#ff0000\"/></defs><use xlink:href=\"#r\"/>"));
        
        assertEquals(RED, image.getRGB(10, 10));
    }
    
    @Test
    void urlToImage_ok_readsLocalFileUrl(@TempDir Path tempDir) throws Exception {
        Path png = writePng(tempDir, new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB));
        
        BufferedImage image = (BufferedImage) imageConvertSupport.urlToImage(png.toUri().toString());
        
        assertEquals(120, image.getWidth());
        assertEquals(80, image.getHeight());
    }
    
    // Lower-casing with a Turkish or Azerbaijani default locale turns "FILE" into "fıle", which made the scheme look unsupported
    @Test
    void urlToImage_ok_readsAFileUrlWithAnUpperCaseSchemeWhateverTheDefaultLocale(@TempDir Path tempDir) throws Exception {
        Path png = writePng(tempDir, new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB));
        String url = "FILE" + png.toUri().toString().substring("file".length());
        
        Locale defaultLocale = Locale.getDefault();
        Locale displayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale formatLocale = Locale.getDefault(Locale.Category.FORMAT);
        try {
            for (Locale locale : new Locale[] { Locale.ENGLISH, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("az-AZ") }) {
                Locale.setDefault(locale);
                
                BufferedImage image = (BufferedImage) imageConvertSupport.urlToImage(url);
                
                assertEquals(120, image.getWidth(), locale.toLanguageTag());
            }
        } finally {
            Locale.setDefault(defaultLocale);
            Locale.setDefault(Locale.Category.DISPLAY, displayLocale);
            Locale.setDefault(Locale.Category.FORMAT, formatLocale);
        }
    }
    
    @Test
    void urlToImage_ng_timesOutOnServerThatTricklesBytes(@TempDir Path tempDir) throws Exception {
        byte[] png = Files.readAllBytes(writePng(tempDir, new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)));
        
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread sender = new Thread(() -> trickle(server, png), "trickling-server");
            sender.setDaemon(true);
            sender.start();
            
            long start = System.nanoTime();
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> imageConvertSupport.urlToImage("http://127.0.0.1:" + server.getLocalPort() + "/image.png"));
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            
            assertTrue(e.getMessage().contains("Timed out"), e.getMessage());
            assertTrue(elapsedSeconds < McpServerConfig.IMAGE_URL_FETCH_TIMEOUT_SECONDS + 5, "took " + elapsedSeconds + "s");
        }
    }
    
    @Test
    void urlToImage_ng_rejectsImageAboveThePixelLimit(@TempDir Path tempDir) throws Exception {
        // 64 million pixels, yet only a few megabytes in memory and a few kilobytes on disk
        Path png = writePng(tempDir, new BufferedImage(8000, 8000, BufferedImage.TYPE_BYTE_BINARY));
        
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> imageConvertSupport.urlToImage(png.toUri().toString()));
        
        assertTrue(e.getMessage().contains("above the limit"), e.getMessage());
    }
    
    @Test
    void urlToImage_ng_rejectsNetworkShareFileUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> imageConvertSupport.urlToImage("file://192.0.2.1/share/image.png"));
    }
    
    @Test
    void urlToImage_ng_rejectsUnsupportedScheme() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> imageConvertSupport.urlToImage("ftp://127.0.0.1/image.png"));
        
        assertTrue(e.getMessage().contains("Only http, https and file URLs are supported"), e.getMessage());
    }
    
    private Path writePng(Path dir, BufferedImage image) throws Exception {
        Path png = dir.resolve("image.png");
        ImageIO.write(image, "png", png.toFile());
        return png;
    }
    
    // Answers one request with a valid PNG, sent one byte per second.
    private void trickle(ServerSocket server, byte[] png) {
        try (Socket socket = server.accept()) {
            socket.getInputStream().read(new byte[4096]);
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            for (byte b : png) {
                out.write(b);
                out.flush();
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            // The client closed the connection on timeout
        }
    }
    
    private String circleSvg(int width, int height) {
        return circleSvg(width, height, null);
    }
    
    private String circleSvg(int width, int height, String viewBox) {
        return "<svg width=\"" + width + "\" height=\"" + height + "\""
                + (viewBox == null ? "" : " viewBox=\"" + viewBox + "\"")
                + " xmlns=\"http://www.w3.org/2000/svg\">"
                + "<circle cx=\"" + (width / 2) + "\" cy=\"" + (height / 2)
                + "\" r=\"30\" fill=\"red\" stroke=\"black\" stroke-width=\"2\"/>"
                + "</svg>";
    }
    
    // Style sheet, percentages, em lengths, text and a gradient: the parts of an SVG whose rendering depends on the CSS engine Batik keeps on the document.
    private String styledSvg() {
        return "<svg width=\"420\" height=\"260\" viewBox=\"0 0 420 260\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<style>.box { fill: url(#g); stroke: #c39; stroke-width: 2; } .lbl { font-family: sans-serif; font-size: 1.2em; }</style>"
                + "<defs><linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#0af\"/><stop offset=\"1\" stop-color=\"#fa0\"/></linearGradient></defs>"
                + "<g style=\"font-size:14px\">"
                + "<rect class=\"box\" x=\"10\" y=\"10\" width=\"45%\" height=\"30%\" rx=\"8\"/>"
                + "<text class=\"lbl\" x=\"50%\" y=\"60%\" text-anchor=\"middle\">Customer</text>"
                + "<rect class=\"box\" x=\"60%\" y=\"70%\" width=\"10em\" height=\"3em\"/>"
                + "</g></svg>";
    }
    
    private String svgWith(String content) {
        return "<svg width=\"20\" height=\"20\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                + content
                + "</svg>";
    }
    
    private BufferedImage render(String svgCode) {
        ParsedSvg svg = imageConvertSupport.parseSvg(svgCode);
        return imageConvertSupport.rasterize(svg, svg.naturalWidth(), svg.naturalHeight());
    }
    
    // Refusing an SVG is as safe as drawing it without what it refers to, so either outcome passes; null stands for a refusal
    private BufferedImage renderOrNull(String svgCode) {
        try {
            return render(svgCode);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    private ParsedSvg parseOrNull(String svgCode) {
        try {
            return imageConvertSupport.parseSvg(svgCode);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    private static BufferedImage filledImage(int argb) {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }
    
    // Counts every request it receives, whatever it asks for, so that a test can tell whether anything was fetched
    private static final class RecordingServer implements AutoCloseable {
        
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        
        RecordingServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();
        }
        
        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + path;
        }
        
        int requests() {
            return requests.get();
        }
        
        @Override
        public void close() {
            server.stop(0);
        }
    }
    
    private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        int[] expectedPixels = expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth());
        int[] actualPixels = actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth());
        assertArrayEquals(expectedPixels, actualPixels);
    }
    
    private static int alpha(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }
    
    // HotSpot counts the bytes each thread allocates, which shows whether a call allocated an image at all.
    private static long allocatedBytesOfThisThread() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }
}
