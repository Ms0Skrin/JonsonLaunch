package home.lite.tv;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

import javax.crypto.Cipher;

/**
 * Минимальный клиент ADB к отладке по сети самого телевизора (127.0.0.1:5555).
 * Обычное приложение не может молча установить APK, а оболочка adb — может: так ставится обновление
 * без вопросов. Ключ лаунчера телевизор один раз просит разрешить, дальше пускает сам.
 */
final class SelfAdb implements AutoCloseable {

    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** DigestInfo SHA-1: adb подписывает 20-байтовый токен как готовый хэш. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int maxData = 4096;

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    /** Подключиться и пройти авторизацию; ждёт до waitMs, пока на ТВ нажмут «Разрешить». */
    SelfAdb(Context c, int waitMs) throws IOException { this(c, waitMs, true); }

    /** offerKey=false: только проверить, разрешён ли уже ключ, не вызывая окно на ТВ. */
    SelfAdb(Context c, int waitMs, boolean offerKey) throws IOException {
        KeyPair keys = loadKeys(c);
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        socket.setSoTimeout(waitMs);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) {
                maxData = Math.min(MAX_DATA, m.arg1);
                return;
            }
            if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("adb: неожиданный ответ");
            if (!signed) {
                signed = true;
                send(A_AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
            } else if (!offerKey) {
                close();
                throw new IOException("adb: ключ не разрешён");
            } else {
                // Ключ ещё не разрешён — ТВ покажет окно «Разрешить отладку»
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob((RSAPublicKey) keys.getPublic()));
            }
        }
    }

    /** Разрешён ли уже ключ лаунчера (без окна на ТВ). Вызывать не из UI-потока. */
    static boolean isAuthorized(Context c) {
        try (SelfAdb adb = new SelfAdb(c, 3000, false)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Выполнить команду, передав data в stdin; вернуть вывод. */
    String exec(String command, InputStream data) throws IOException {
        int local = 1;
        send(A_OPEN, local, 0, ("exec:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        Msg m = read();
        if (m.cmd != A_OKAY) throw new IOException("adb: команда отклонена");
        int remote = m.arg0;
        if (data != null) {
            byte[] buf = new byte[maxData];
            int n;
            while ((n = data.read(buf)) > 0) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                send(A_WRTE, local, remote, chunk);
                // Ждём подтверждения каждого куска, попутно собирая вывод
                while (true) {
                    Msg r = read();
                    if (r.cmd == A_OKAY) break;
                    if (r.cmd == A_CLSE) throw new IOException("adb: поток закрыт");
                }
            }
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Msg r = read();
            if (r.cmd == A_WRTE) {
                result.write(r.data);
                send(A_OKAY, local, remote, null);
            } else if (r.cmd == A_CLSE) {
                break;
            }
        }
        return result.toString("UTF-8");
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- Протокол

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        m.data = new byte[h.getInt()];
        in.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- Ключ

    private static KeyPair loadKeys(Context c) throws IOException {
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                String[] p = new String(readAll(pub), StandardCharsets.US_ASCII).split(",");
                RSAPublicKey pk = (RSAPublicKey) kf.generatePublic(
                        new RSAPublicKeySpec(new BigInteger(p[0]), new BigInteger(p[1])));
                PrivateKey sk = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv)));
                return new KeyPair(pk, sk);
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            RSAPublicKey pk = (RSAPublicKey) kp.getPublic();
            write(priv, kp.getPrivate().getEncoded());
            write(pub, (pk.getModulus() + "," + pk.getPublicExponent()).getBytes(StandardCharsets.US_ASCII));
            return kp;
        } catch (Exception e) {
            throw new IOException("adb: ключ", e);
        }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, key);
            rsa.update(SHA1_PREFIX);
            rsa.update(token);
            return rsa.doFinal();
        } catch (Exception e) {
            throw new IOException("adb: подпись", e);
        }
    }

    /** Открытый ключ в формате adb: структура RSAPublicKey в little-endian, base64 и имя. */
    private static byte[] publicKeyBlob(RSAPublicKey key) {
        final int words = 2048 / 32;
        BigInteger n = key.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) {
            b.putInt(n.mod(r32).intValue());
            n = n.shiftRight(32);
        }
        for (int i = 0; i < words; i++) {
            b.putInt(rr.mod(r32).intValue());
            rr = rr.shiftRight(32);
        }
        b.putInt(key.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " JonsonLaunch@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream s = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            new DataInputStream(s).readFully(b);
            return b;
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream s = new FileOutputStream(f)) {
            s.write(data);
        }
    }
}
