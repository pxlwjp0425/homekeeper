package com.laopeng.homekeeper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 通过本机 adb（127.0.0.1:5555，盒子 adbd 免鉴权）执行一条 shell 命令。
 *
 * <p>为什么需要它：这台 ROM 每次重启都会把显示密度重置回 320，而
 * {@code Settings.Secure.display_density_forced} 写了也不被 WMS 读取，
 * 唯一生效的办法是执行 {@code wm density}；但 App 身份执行 {@code wm} 会因
 * {@code INTERACT_ACROSS_USERS_FULL}（signature 权限，拿不到）被拒。
 * 所以绕道本地 adb，以 shell 身份执行。</p>
 */
final class AdbShell {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_WRTE = 0x45545257;
    private static final int A_CLSE = 0x434c5345;

    private AdbShell() {
    }

    /**
     * 执行一条「等待 stdin 的命令」（如 {@code cat > /path}），把 data 作为 stdin 传进去。
     *
     * <p>为什么需要：本机 fuse 对 App 进程写 /sdcard 返回 EPERM（scoped storage 判定），
     * 但同一条路径用 shell 身份写是允许的，所以保存配置时绕道本地 adb。</p>
     */
    static boolean execWithInput(String cmd, String data) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
            s.setSoTimeout(6000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());

            write(out, A_CNXN, 0x01000001, 4096, "host::\0".getBytes("UTF-8"));
            int[] h = read(in);
            android.util.Log.i("HKADB", "cnxn=" + Integer.toHexString(h[0]));
            if (h[0] != A_CNXN) {
                return false;
            }
            skip(in, h[3]);

            write(out, A_OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
            h = read(in);
            int remote = h[1];
            android.util.Log.i("HKADB", "open=" + Integer.toHexString(h[0]) + " remote=" + remote);
            if (h[0] != A_OKAY) {
                return false;
            }
            skip(in, h[3]);

            byte[] all = data.getBytes("UTF-8");
            int off = 0;
            while (off < all.length) {
                int len = Math.min(3000, all.length - off);
                byte[] part = new byte[len];
                System.arraycopy(all, off, part, 0, len);
                write(out, A_WRTE, 1, remote, part);
                off += len;
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                }
            }
            // 服务器可能在收完数据后就关连接，CLSE 写失败是正常的，不能因此判定整体失败
            try {
                write(out, A_CLSE, 1, remote, new byte[0]);
            } catch (Throwable ignored) {
            }
            android.util.Log.i("HKADB", "sent=" + all.length + " bytes");
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
            return true;
        } catch (Throwable t) {
            android.util.Log.w("HKADB", "execWithInput failed: " + t);
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static String exec(String cmd) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
            s.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());

            write(out, A_CNXN, 0x01000001, 4096, "host::\0".getBytes("UTF-8"));
            int[] h = read(in);
            if (h[0] == A_AUTH) {
                return "AUTH_REQUIRED";
            }
            if (h[0] != A_CNXN) {
                return "BAD_CNXN_" + Integer.toHexString(h[0]);
            }
            skip(in, h[3]);

            write(out, A_OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
            h = read(in);
            int remote = h[1];
            if (h[0] != A_OKAY) {
                return "OPEN_FAIL_" + Integer.toHexString(h[0]);
            }
            skip(in, h[3]);

            write(out, A_WRTE, 1, remote, (cmd + "\n").getBytes("UTF-8"));

            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                h = read(in);
                int id = h[0];
                int arg0 = h[1];
                int arg1 = h[2];
                int len = h[3];
                byte[] data = new byte[len];
                if (len > 0) {
                    in.readFully(data);
                }
                if (id == A_WRTE) {
                    sb.append(new String(data, "UTF-8"));
                    write(out, A_OKAY, arg1, arg0, new byte[0]);
                } else if (id == A_CLSE) {
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "ERR:" + t;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void write(DataOutputStream out, int cmd, int a0, int a1, byte[] data)
            throws IOException {
        byte[] hdr = new byte[24];
        putLE(hdr, 0, cmd);
        putLE(hdr, 4, a0);
        putLE(hdr, 8, a1);
        putLE(hdr, 12, data.length);
        putLE(hdr, 16, checksum(data));
        putLE(hdr, 20, cmd ^ 0xffffffff);
        out.write(hdr);
        if (data.length > 0) {
            out.write(data);
        }
        out.flush();
    }

    private static int[] read(DataInputStream in) throws IOException {
        byte[] hdr = new byte[24];
        in.readFully(hdr);
        return new int[]{getLE(hdr, 0), getLE(hdr, 4), getLE(hdr, 8), getLE(hdr, 12)};
    }

    private static void skip(DataInputStream in, int n) throws IOException {
        int left = n;
        while (left > 0) {
            long r = in.skip(left);
            if (r <= 0) {
                in.readByte();
                left--;
            } else {
                left -= (int) r;
            }
        }
    }

    private static int getLE(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void putLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static int checksum(byte[] d) {
        int s = 0;
        for (byte x : d) {
            s += (x & 0xff);
        }
        return s;
    }
}
