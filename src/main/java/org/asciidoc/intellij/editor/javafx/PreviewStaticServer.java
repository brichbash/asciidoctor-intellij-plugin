package org.asciidoc.intellij.editor.javafx;

import com.intellij.ide.browsers.OpenInBrowserRequest;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Url;
import com.intellij.util.Urls;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.asciidoc.intellij.editor.browser.BrowserPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.ide.BuiltInServerManager;
import org.jetbrains.ide.HttpRequestHandler;
import org.jetbrains.io.FileResponses;
import org.jetbrains.io.Responses;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PreviewStaticServer extends HttpRequestHandler {
  private Logger log = Logger.getInstance(PreviewStaticServer.class);

  private static final Logger LOG = Logger.getInstance(PreviewStaticServer.class);
  private static final String PREFIX = "/ead61b63-b0a6-4ff2-a49a-86be75ccfd1a/";
  private static final Pattern PAYLOAD_PATTERN = Pattern.compile("((?<contentType>[^/]*)/(?<fileName>[a-zA-Z0-9./_-]*))|(?<action>(source|image))");

  private BrowserPanel browserPanel;

  // every time the plugin starts up, assume resources could have been modified
  private static final long LAST_MODIFIED = System.currentTimeMillis();

  public static PreviewStaticServer getInstance() {
    return HttpRequestHandler.Companion.getEP_NAME().findExtension(PreviewStaticServer.class);
  }

  @NotNull
  public static String createCSP(@NotNull List<String> scripts, @NotNull List<String> styles) {
    return "default-src 'none'; script-src " + StringUtil.join(scripts, " ") + "; "
      + "style-src https: " + StringUtil.join(styles, " ") + "; "
      + "img-src file: *; connect-src 'none'; font-src *; " +
      "object-src 'none'; media-src 'none'; child-src 'none';";
  }

  @NotNull
  private static String getStaticUrl(@NotNull String staticPath) {
    Url url = Urls.parseEncoded("http://localhost:" + BuiltInServerManager.getInstance().getPort() + PREFIX + staticPath);
    return BuiltInServerManager.getInstance().addAuthToken(Objects.requireNonNull(url)).toExternalForm();
  }

  @NotNull
  public static String getScriptUrl(@NotNull String scriptFileName) {
    return getStaticUrl("scripts/" + scriptFileName);
  }

  @NotNull
  public static String getStyleUrl(@NotNull String scriptFileName) {
    return getStaticUrl("styles/" + scriptFileName);
  }

  public static Url getFileUrl(OpenInBrowserRequest request, VirtualFile file) {
    Url url;
    try {
      url = Urls.parseEncoded("http://localhost:" + BuiltInServerManager.getInstance().getPort() + PREFIX + "source?file=" +
        URLEncoder.encode(file.getPath(), StandardCharsets.UTF_8.toString()));
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("can't encode");
    }
    if (request.isAppendAccessToken()) {
      url = BuiltInServerManager.getInstance().addAuthToken(Objects.requireNonNull(url));
    }
    return url;
  }

  @Override
  public boolean isSupported(@NotNull FullHttpRequest request) {
    return super.isSupported(request) && request.uri().startsWith(PREFIX);
  }

  @Override
  public boolean process(@NotNull QueryStringDecoder urlDecoder,
                         @NotNull FullHttpRequest request,
                         @NotNull ChannelHandlerContext context) {
    final String path = urlDecoder.path();
    if (!path.startsWith(PREFIX)) {
      throw new IllegalStateException("prefix should have been checked by #isSupported");
    }

    final String payLoad = path.substring(PREFIX.length());

    Matcher matcher = PAYLOAD_PATTERN.matcher(payLoad);

    if (!matcher.matches()) {
      log.warn("won't deliver resource to preview as it might be unsafe: " + payLoad);
      return false;
    }

    final String contentType = matcher.group("contentType");
    final String fileName = matcher.group("fileName");
    final String action = matcher.group("action");

    if ("scripts".equals(contentType)) {
      sendResource(request,
        context.channel(),
        JavaFxHtmlPanel.class,
        fileName);
    } else if ("styles".equals(contentType)) {
      sendResource(request,
        context.channel(),
        JavaFxHtmlPanel.class,
        fileName);
    } else if ("source".equals(action)) {
      String file = urlDecoder.parameters().get("file").get(0);
      VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(file);
      sendDocument(request, vf, context.channel());
    } else if ("image".equals(action)) {
      String file = urlDecoder.parameters().get("file").get(0);
      String mac = urlDecoder.parameters().get("mac").get(0);
      return sendImage(request, file, mac, context.channel());
    } else {
      return false;
    }

    return true;
  }

  private boolean sendImage(FullHttpRequest request, String file, String mac, Channel channel) {
    if (browserPanel == null) {
      return false;
    }

    byte[] image = browserPanel.getImage(file, mac);
    if (image != null) {
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(image));
      if (file.endsWith(".png")) {
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "image/png");
      } else if (file.endsWith(".jpg")) {
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "image/jpsg");
      } else if (file.endsWith(".svg")) {
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "image/svg+xml");
      } else {
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
      }
      response.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=3600, private, must-revalidate");
      response.headers().set(HttpHeaderNames.ETAG, Long.toString(LAST_MODIFIED));
      Responses.send(response, channel, request);
      return true;
    } else {
      return false;
    }
  }

  private void sendDocument(FullHttpRequest request, VirtualFile file, Channel channel) {
    synchronized (this) {
      if (browserPanel == null) {
        browserPanel = new BrowserPanel();
      }

      ReadAction.compute(() -> {
          String html = browserPanel.getHtml(file);
          FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(html.getBytes(StandardCharsets.UTF_8)));
          response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
          response.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=5, private, must-revalidate");
          response.headers().set(HttpHeaderNames.ETAG, Long.toString(LAST_MODIFIED));
          Responses.send(response, channel, request);
          return true;
        }
      );
    }
  }

  private static void sendResource(@NotNull HttpRequest request,
                                   @NotNull Channel channel,
                                   @NotNull Class<?> clazz,
                                   @NotNull String resourceName) {
    /*
    // API incompatible with older versions of IntelliJ
    if (FileResponses.INSTANCE.checkCache(request, channel, lastModified)) {
      return;
    }
    */

    byte[] data;
    try (InputStream inputStream = clazz.getResourceAsStream(resourceName)) {
      if (inputStream == null) {
        Responses.send(HttpResponseStatus.NOT_FOUND, channel, request);
        return;
      }

      data = FileUtilRt.loadBytes(inputStream);
    } catch (IOException e) {
      LOG.warn(e);
      Responses.send(HttpResponseStatus.INTERNAL_SERVER_ERROR, channel, request);
      return;
    }

    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(data));
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, FileResponses.INSTANCE.getContentType(resourceName));
    response.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=3600, private, must-revalidate");
    response.headers().set(HttpHeaderNames.ETAG, Long.toString(LAST_MODIFIED));
    Responses.send(response, channel, request);
  }
}
