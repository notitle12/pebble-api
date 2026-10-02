package com.pebble.api.global.media;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;

@Component
public class WebpImageProcessor implements ImageProcessor {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final long MAX_PIXELS = 20_000_000L;
    private static final int MAX_EDGE = 8_000;
    private static final int DISPLAY_EDGE = 2_560;
    private static final int THUMBNAIL_EDGE = 480;

    @Override
    public ProcessedImage process(byte[] source) {
        if (source == null || source.length == 0) {
            throw invalidImage();
        }
        if (source.length > MAX_BYTES) {
            throw tooLarge();
        }
        String format = formatFromSignature(source);
        if (format == null) {
            throw unsupportedMediaType();
        }
        if ((format.equals("png") && containsApngAnimation(source))
                || (format.equals("webp") && containsAnimatedWebp(source))) {
            throw unsupportedMediaType();
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) throw invalidImage();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                String decodedFormat = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!(format.equals("jpeg") && (decodedFormat.equals("jpeg") || decodedFormat.equals("jpg")))
                        && !decodedFormat.equals(format)) throw invalidImage();
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw invalidImage();
                if (width > MAX_EDGE || height > MAX_EDGE || (long) width * height > MAX_PIXELS) throw tooLarge();
                int imageCount = reader.getNumImages(true);
                if (imageCount > 1) throw unsupportedMediaType();
                if (imageCount != 1) throw invalidImage();
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) throw invalidImage();
                return new ProcessedImage(encode(resize(decoded, DISPLAY_EDGE)), encode(resize(decoded, THUMBNAIL_EDGE)));
            } finally {
                reader.dispose();
            }
        } catch (ApplicationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidImage();
        }
    }

    private BufferedImage resize(BufferedImage source, int maxEdge) {
        double scale = Math.min(1d, (double) maxEdge / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int imageType = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage result = new BufferedImage(width, height, imageType);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private byte[] encode(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) throw new IOException("WebP writer unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            String lossyType = null;
            if (parameters.getCompressionTypes() != null) {
                for (String type : parameters.getCompressionTypes()) {
                    if ("Lossy".equalsIgnoreCase(type)) lossyType = type;
                }
            }
            if (lossyType == null) throw new IOException("WebP lossy compression unavailable");
            parameters.setCompressionType(lossyType);
            parameters.setCompressionQuality(0.82f);
            writer.write(null, new IIOImage(image, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private String formatFromSignature(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) return "jpeg";
        if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 0x0d && data[5] == 0x0a && data[6] == 0x1a && data[7] == 0x0a) return "png";
        if (data.length >= 12 && ascii(data, 0, "RIFF") && ascii(data, 8, "WEBP")) return "webp";
        return null;
    }

    private boolean containsApngAnimation(byte[] data) {
        int offset = 8;
        while (offset + 12 <= data.length) {
            long length = uint32(data, offset);
            if (ascii(data, offset + 4, "acTL")) return true;
            if (length > data.length - offset - 12) return false;
            offset += (int) length + 12;
            if (ascii(data, offset - (int) length - 8, "IEND")) return false;
        }
        return false;
    }

    private boolean containsAnimatedWebp(byte[] data) {
        int offset = 12;
        while (offset + 8 <= data.length) {
            if (ascii(data, offset, "ANIM") || ascii(data, offset, "ANMF")) return true;
            if (ascii(data, offset, "VP8X") && offset + 9 <= data.length && (data[offset + 8] & 0x02) != 0) return true;
            long length = uint32LittleEndian(data, offset + 4);
            if (length > data.length - offset - 8) return false;
            offset += 8 + (int) length + ((int) length & 1);
        }
        return false;
    }

    private long uint32LittleEndian(byte[] data, int offset) {
        return (data[offset] & 0xffL) | ((long) (data[offset + 1] & 0xff) << 8)
                | ((long) (data[offset + 2] & 0xff) << 16) | ((long) (data[offset + 3] & 0xff) << 24);
    }

    private long uint32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24) | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xffL);
    }

    private boolean ascii(byte[] data, int offset, String value) {
        if (offset < 0 || offset + value.length() > data.length) return false;
        for (int i = 0; i < value.length(); i++) if (data[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private ApplicationException invalidImage() {
        return new ApplicationException(GlobalErrorCode.INVALID_MEDIA);
    }

    private ApplicationException unsupportedMediaType() {
        return new ApplicationException(GlobalErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    private ApplicationException tooLarge() {
        return new ApplicationException(GlobalErrorCode.MEDIA_TOO_LARGE);
    }
}
