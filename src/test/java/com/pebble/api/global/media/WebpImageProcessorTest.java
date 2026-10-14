package com.pebble.api.global.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.global.exception.ApplicationException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class WebpImageProcessorTest {
    private final WebpImageProcessor processor = new WebpImageProcessor();

    @Test
    void processesJpegPngAndWebpIntoWebpDerivativesWithoutUpscaling() throws Exception {
        for (String format : new String[]{"jpeg", "png", "webp"}) {
            ProcessedImage result = processor.process(imageBytes(format, 320, 200));
            BufferedImage display = ImageIO.read(new java.io.ByteArrayInputStream(result.display()));
            BufferedImage thumbnail = ImageIO.read(new java.io.ByteArrayInputStream(result.thumbnail()));
            assertThat(display.getWidth()).isEqualTo(320);
            assertThat(display.getHeight()).isEqualTo(200);
            assertThat(thumbnail.getWidth()).isEqualTo(320);
            assertThat(thumbnail.getHeight()).isEqualTo(200);
            assertThat(result.display()).containsSubsequence((byte) 'W', (byte) 'E', (byte) 'B', (byte) 'P');
            assertThat(result.thumbnail()).containsSubsequence((byte) 'W', (byte) 'E', (byte) 'B', (byte) 'P');
        }
    }

    @Test
    void capsDerivativeLongEdgesAndRejectsOversizedOrUnsupportedInput() throws Exception {
        ProcessedImage result = processor.process(imageBytes("png", 3000, 1000));
        BufferedImage display = ImageIO.read(new java.io.ByteArrayInputStream(result.display()));
        BufferedImage thumbnail = ImageIO.read(new java.io.ByteArrayInputStream(result.thumbnail()));
        assertThat(display.getWidth()).isEqualTo(2560);
        assertThat(display.getHeight()).isEqualTo(853);
        assertThat(thumbnail.getWidth()).isEqualTo(480);
        assertThat(thumbnail.getHeight()).isEqualTo(160);

        assertThatThrownBy(() -> processor.process(imageBytes("png", 8001, 1)))
                .isInstanceOf(ApplicationException.class)
                .extracting(exception -> ((ApplicationException) exception).error().code()).isEqualTo("MEDIA_TOO_LARGE");
        assertThatThrownBy(() -> processor.process(new byte[]{'G', 'I', 'F', '8', '9', 'a'}))
                .isInstanceOf(ApplicationException.class)
                .extracting(exception -> ((ApplicationException) exception).error().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void rejectsAnimatedImagesOversizedFilesAndMalformedContent() throws Exception {
        byte[] png = imageBytes("png", 8, 8);
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
        tooLarge[0] = (byte) 0x89;
        tooLarge[1] = 'P';
        tooLarge[2] = 'N';
        tooLarge[3] = 'G';
        tooLarge[4] = 13;
        tooLarge[5] = 10;
        tooLarge[6] = 26;
        tooLarge[7] = 10;

        assertMediaError(withApngControl(png), "UNSUPPORTED_MEDIA_TYPE");
        assertMediaError(animatedWebp(), "UNSUPPORTED_MEDIA_TYPE");
        assertMediaError(tooLarge, "MEDIA_TOO_LARGE");
        assertMediaError(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2}, "INVALID_MEDIA");
        assertMediaError(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1}, "INVALID_MEDIA");
        assertMediaError(new byte[]{'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'}, "INVALID_MEDIA");
        assertMediaError(new byte[0], "INVALID_MEDIA");
    }

    @Test
    void discardsExifMetadataDuringReencoding() throws Exception {
        byte[] jpeg = imageBytes("jpeg", 40, 30);
        byte[] withExif = insertExif(jpeg);
        assertThat(withExif).containsSubsequence("Exif".getBytes(StandardCharsets.US_ASCII));

        ProcessedImage result = processor.process(withExif);
        assertThat(new String(result.display(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif").doesNotContain("EXIF");
        assertThat(new String(result.thumbnail(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif").doesNotContain("EXIF");
    }

    @Test
    void preservesPngTransparencyInWebpDerivatives() throws Exception {
        ProcessedImage result = processor.process(transparentPng());

        for (byte[] derivative : new byte[][]{result.display(), result.thumbnail()}) {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(derivative));
            assertThat(decoded.getColorModel().hasAlpha()).isTrue();
            assertThat(decoded.getRGB(0, 0) >>> 24).isZero();
            assertThat(decoded.getRGB(1, 0) >>> 24).isBetween(75, 180);
        }
    }

    private byte[] imageBytes(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String imageIoFormat = "jpeg".equals(format) ? "jpg" : format;
        assertThat(ImageIO.write(image, imageIoFormat, output)).isTrue();
        return output.toByteArray();
    }

    private byte[] transparentPng() throws IOException {
        BufferedImage image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x00ff0000);
        image.setRGB(1, 0, 0x8000ff00);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }

    private void assertMediaError(byte[] image, String errorCode) {
        assertThatThrownBy(() -> processor.process(image))
                .isInstanceOf(ApplicationException.class)
                .extracting(exception -> ((ApplicationException) exception).error().code()).isEqualTo(errorCode);
    }

    private byte[] withApngControl(byte[] png) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(png, 0, 33);
        byte[] chunkHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(8).put("acTL".getBytes(StandardCharsets.US_ASCII)).array();
        byte[] chunkData = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(2).putInt(0).array();
        CRC32 crc = new CRC32();
        crc.update("acTL".getBytes(StandardCharsets.US_ASCII));
        crc.update(chunkData);
        output.write(chunkHeader);
        output.write(chunkData);
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) crc.getValue()).array());
        output.write(png, 33, png.length - 33);
        return output.toByteArray();
    }

    private byte[] animatedWebp() {
        ByteBuffer webp = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        webp.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        webp.putInt(22);
        webp.put("WEBPVP8X".getBytes(StandardCharsets.US_ASCII));
        webp.putInt(10);
        webp.put((byte) 0x02);
        webp.put(new byte[9]);
        return webp.array();
    }

    private byte[] insertExif(byte[] jpeg) throws IOException {
        byte[] exifMarker = new byte[]{(byte) 0xff, (byte) 0xe1, 0, 10, 'E', 'x', 'i', 'f', 0, 0, 1, 2};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(jpeg, 0, 2);
        output.write(exifMarker);
        output.write(jpeg, 2, jpeg.length - 2);
        return output.toByteArray();
    }
}
