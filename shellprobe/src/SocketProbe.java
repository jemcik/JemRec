import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Probes which IPC channels a shell-UID process can offer to the APP.
 *
 * This matters because ADB cannot be the runtime transport. With Wi-Fi off,
 * adbd's listener is torn down entirely (measured), and the shell UID is not
 * permitted to set service.adb.tcp.port to pin a stable one. So ADB can only
 * BOOTSTRAP a long-lived shell process - which does outlive the ADB connection,
 * also measured - and everything after that needs a channel that does not
 * involve adbd at all.
 *
 * Two candidates, tested side by side:
 *
 *   ABSTRACT UNIX SOCKET - already measured as BLOCKED. SELinux refuses an
 *   untrusted_app connecting to the shell domain's unix_stream_socket:
 *   "IOException: Permission denied". Kept here so the comparison is visible
 *   in one run rather than remembered.
 *
 *   TCP ON LOOPBACK - the hypothesis. Any app holding INTERNET may open a TCP
 *   connection, and 127.0.0.1 exists with every radio off. If SELinux treats
 *   this differently from the abstract namespace, it is the channel the audio
 *   stream should use.
 *
 * A filesystem socket is not a candidate: it would have to live under
 * /data/local/tmp, which is shell-only and which the app cannot traverse.
 */
public final class SocketProbe {

    private static final String ABSTRACT_NAME = "jemrec_probe";
    private static final int TCP_PORT = 28471;

    public static void main(String[] args) throws Exception {
        final int uid = Process.myUid();

        // TCP listener, bound explicitly to loopback so it is never exposed to
        // any network even for an instant.
        final ServerSocket tcp = new ServerSocket(
                TCP_PORT, 4, InetAddress.getByName("127.0.0.1"));
        System.out.println("probe: TCP listening on 127.0.0.1:" + TCP_PORT + " as uid " + uid);
        System.out.flush();

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Socket client = tcp.accept();
                        OutputStream out = client.getOutputStream();
                        out.write(("hello over tcp from uid " + uid + "\n").getBytes("UTF-8"));
                        out.flush();
                        client.close();
                        System.out.println("probe: served a TCP client");
                        System.out.flush();
                    } catch (Exception e) {
                        System.out.println("probe: tcp accept failed: " + e);
                        System.out.flush();
                        return;
                    }
                }
            }
        }).start();

        LocalServerSocket server = new LocalServerSocket(ABSTRACT_NAME);
        System.out.println("probe: abstract listening on " + ABSTRACT_NAME + " as uid " + uid);
        System.out.flush();

        while (true) {
            LocalSocket client = server.accept();
            OutputStream out = client.getOutputStream();
            out.write(("hello over abstract from uid " + uid + "\n").getBytes("UTF-8"));
            out.flush();
            client.close();
            System.out.println("probe: served an abstract client");
            System.out.flush();
        }
    }
}
