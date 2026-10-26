package net.rs256.jukebox.disc;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * {@code .disc} files: a ZIP of {@code header.json} and {@code ticks.json}, both plain JSON so
 * {@code unzip -p x.disc ticks.json} works. Also converts to and from the uncompressed
 * {@code .disc.json} ({@code {"schema":0,"header":{...},"ticks":[...]}}).
 */
public final class DiscContainer {
    public static final String HEADER_FILE = "header.json";

    public enum Codec {
        DEFLATE,
        RAW
    }

    private DiscContainer() {
    }

    /** Packs {@code ticks.json} of a finished {@code .recording/} directory with {@code header} into a new {@code disc}. */
    public static void pack(Path recordingDir, Header header, Path disc, Codec codec) throws IOException {
        byte[] headerBytes = (DiscWriter.PRETTY_GSON.toJson(DiscWriter.headerToJson(header)) + "\n").getBytes(StandardCharsets.UTF_8);
        long time = header.created().toEpochMilli();

        OutputStream out = Files.newOutputStream(disc, StandardOpenOption.CREATE_NEW);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            putBytes(zip, HEADER_FILE, headerBytes, codec, time);
            putFile(zip, DiscWriter.TICKS_FILE, recordingDir.resolve(DiscWriter.TICKS_FILE), codec, time);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(disc);
            throw e;
        }
    }

    /** Deletes a {@code .recording/} directory; fails if it holds anything besides the files {@link DiscWriter} creates. */
    public static void deleteRecording(Path recordingDir) throws IOException {
        Files.deleteIfExists(recordingDir.resolve(DiscWriter.META_FILE));
        Files.deleteIfExists(recordingDir.resolve(DiscWriter.TICKS_FILE));
        Files.delete(recordingDir);
    }

    public static Header readHeader(Path disc) throws IOException {
        try (Reader in = openEntry(disc, HEADER_FILE)) {
            return DiscReader.readHeader(in);
        }
    }

    public static DiscReader openTicks(Path disc) throws IOException {
        Reader in = openEntry(disc, DiscWriter.TICKS_FILE);
        try {
            return DiscReader.openTicks(in);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /** Writes {@code disc} as a new {@code discJson}, one tick per line like {@code ticks.json}. */
    public static void toJson(Path disc, Path discJson) throws IOException {
        Header header = readHeader(disc);
        Writer out = Files.newBufferedWriter(discJson, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try (out; DiscReader ticks = openTicks(disc)) {
            out.write("{\"schema\":" + Header.SCHEMA + ",\"header\":");
            out.write(DiscWriter.GSON.toJson(DiscWriter.headerToJson(header)));
            out.write(",\"ticks\":[");
            boolean first = true;
            for (TickRecord tick = ticks.next(); tick != null; tick = ticks.next()) {
                if (!first) {
                    out.write(',');
                }
                out.write('\n');
                out.write(DiscWriter.GSON.toJson(DiscWriter.tickToJson(tick.tick(), tick.events())));
                first = false;
            }
            out.write("\n]}\n");
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(discJson);
            throw e;
        }
    }

    /** Packs {@code discJson} into a new {@code disc}; the ticks go through a temporary {@code .recording/} next to it. */
    public static void fromJson(Path discJson, Path disc, Codec codec) throws IOException {
        Header header = readJsonHeader(discJson);
        DiscWriter.Meta meta = new DiscWriter.Meta(header.source(), header.created(), header.mcVersion(), header.modVersion(), header.world(), header.timing());
        Path temp = disc.resolveSibling("." + disc.getFileName() + ".convert");

        try {
            Header packed;
            try (DiscReader ticks = DiscReader.openTicks(Files.newBufferedReader(discJson, StandardCharsets.UTF_8));
                 DiscWriter writer = DiscWriter.open(temp, meta)) {
                for (TickRecord tick = ticks.next(); tick != null; tick = ticks.next()) {
                    writer.beginTick(tick.tick());
                    for (JukeboxEvent event : tick.events()) {
                        writer.add(event);
                    }
                    writer.endTick();
                }
                packed = writer.finish(header.durationTicks());
            }
            pack(temp, packed, disc, codec);
        } finally {
            if (Files.exists(temp)) {
                deleteRecording(temp);
            }
        }
    }

    /** Reads only the {@code header} of a {@code .disc.json}, skipping everything else. */
    private static Header readJsonHeader(Path discJson) throws IOException {
        try (JsonReader json = new JsonReader(Files.newBufferedReader(discJson, StandardCharsets.UTF_8))) {
            json.beginObject();
            while (json.hasNext()) {
                if (json.nextName().equals("header")) {
                    return DiscReader.headerFromJson(JsonParser.parseReader(json).getAsJsonObject());
                } else {
                    json.skipValue();
                }
            }
        }
        throw new JsonParseException("missing \"header\"");
    }

    /** Opens {@code name} inside {@code disc}; closing the returned reader closes the file. */
    private static Reader openEntry(Path disc, String name) throws IOException {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(disc)));
        try {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
        zip.close();
        throw new ZipException(disc + " has no " + name);
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] bytes, Codec codec, long time) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(time);
        if (codec == Codec.RAW) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    /** {@link Codec#RAW} needs the CRC before the data. */
    private static void putFile(ZipOutputStream zip, String name, Path file, Codec codec, long time) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(time);
        if (codec == Codec.RAW) {
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(Files.size(file));
            entry.setCrc(crc(file));
        }
        zip.putNextEntry(entry);
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static long crc(Path file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }
}
