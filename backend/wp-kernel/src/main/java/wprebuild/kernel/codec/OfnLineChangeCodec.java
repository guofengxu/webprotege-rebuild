package wprebuild.kernel.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLDataFactory;
import wprebuild.kernel.change.OntologyChange;

/** {@code ofn-lines/1}: one escaped change line per physical line, UTF-8, then gzip (ADR-011). */
public final class OfnLineChangeCodec implements ChangeCodec {

    public static final String ID = "ofn-lines/1";

    private final OWLDataFactory dataFactory;

    public OfnLineChangeCodec() {
        this(OWLManager.getOWLDataFactory());
    }

    public OfnLineChangeCodec(OWLDataFactory dataFactory) {
        this.dataFactory = Objects.requireNonNull(dataFactory, "dataFactory");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public byte[] encode(List<OntologyChange> changes) {
        Objects.requireNonNull(changes, "changes");
        OfnLineDecoder checker = new OfnLineDecoder(OWLManager.getOWLDataFactory());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text(changes, checker).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to gzip ofn-lines/1 payload", ex);
        }
        return bytes.toByteArray();
    }

    @Override
    public List<OntologyChange> decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        String text = gunzip(payload);
        if (text.isEmpty()) {
            return List.of();
        }
        OfnLineDecoder decoder = new OfnLineDecoder(dataFactory);
        String[] lines = text.split("\n", -1);
        List<OntologyChange> changes = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                if (i == lines.length - 1) {
                    continue;
                }
                throw new IllegalArgumentException("empty change line at " + i);
            }
            if (lines[i].indexOf('\r') >= 0) {
                throw new IllegalArgumentException("raw carriage return in change line " + i);
            }
            changes.add(decoder.decode(OfnLines.unescapeLine(lines[i])));
        }
        return List.copyOf(changes);
    }

    private static String text(List<OntologyChange> changes, OfnLineDecoder checker) {
        if (changes.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (OntologyChange change : changes) {
            text.append(OfnLines.escapeLine(OfnLines.encodeLine(change, checker)))
                    .append('\n');
        }
        return text.toString();
    }

    private static String gunzip(byte[] payload) {
        try {
            return utf8(inflateOneMember(payload));
        } catch (IOException ex) {
            throw new IllegalArgumentException("payload is not gzip-compressed ofn-lines/1 text", ex);
        }
    }

    /** One gzip member, CRC checked, no bytes before or after it. */
    private static byte[] inflateOneMember(byte[] payload) throws IOException {
        int offset = headerEnd(payload);
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(payload, offset, payload.length - offset);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int n = inflate(inflater, buffer);
                if (n > 0) {
                    out.write(buffer, 0, n);
                    continue;
                }
                if (inflater.finished()) {
                    break;
                }
                throw new IOException("truncated gzip");
            }
            int trailer = offset + (int) inflater.getBytesRead();
            if (trailer < 0 || trailer + 8 != payload.length) {
                throw new IOException("trailing bytes after gzip member");
            }
            byte[] body = out.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            if (uint32(payload, trailer) != crc.getValue()
                    || uint32(payload, trailer + 4) != (body.length & 0xffffffffL)) {
                throw new IOException("corrupt gzip trailer");
            }
            return body;
        } finally {
            inflater.end();
        }
    }

    private static int inflate(Inflater inflater, byte[] buffer) throws IOException {
        try {
            return inflater.inflate(buffer);
        } catch (DataFormatException ex) {
            throw new IOException("invalid gzip deflate stream", ex);
        }
    }

    private static int headerEnd(byte[] payload) throws IOException {
        if (payload.length < 18 || (payload[0] & 0xff) != 0x1f || (payload[1] & 0xff) != 0x8b) {
            throw new IOException("not a gzip payload");
        }
        if ((payload[2] & 0xff) != 8 || (payload[3] & 0xe0) != 0) {
            throw new IOException("unsupported gzip header");
        }
        int flags = payload[3] & 0xff;
        int offset = 10;
        if ((flags & 0x04) != 0) {
            if (offset + 2 > payload.length) {
                throw new IOException("truncated gzip header");
            }
            int extra = (payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8);
            offset += 2 + extra;
        }
        if ((flags & 0x08) != 0) {
            offset = skipCString(payload, offset);
        }
        if ((flags & 0x10) != 0) {
            offset = skipCString(payload, offset);
        }
        if ((flags & 0x02) != 0) {
            offset += 2;
        }
        if (offset > payload.length - 8) {
            throw new IOException("truncated gzip header");
        }
        return offset;
    }

    private static int skipCString(byte[] payload, int offset) throws IOException {
        while (offset < payload.length) {
            if (payload[offset++] == 0) {
                return offset;
            }
        }
        throw new IOException("truncated gzip header");
    }

    private static long uint32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static String utf8(byte[] bytes) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("payload is not valid UTF-8", ex);
        }
    }
}
