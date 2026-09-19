package com.aaa.notifier.stream;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 연결 두절을 <b>실제로</b> 만들어 내는 테스트용 TCP 중계기 — 소비 계층과 Redis 사이에 끼워 두고 끊었다 되살린다.
 *
 * <p>컨테이너를 중지·재시작하면 호스트 포트가 바뀔 수 있고, {@code pause}는 연결을 끊지 않고 멈춰 세울 뿐이라 재접속 경로를 지나지 않는다. 이 중계기는 같은
 * 포트를 유지한 채 {@link #sever()}로 리스닝 채널과 진행 중인 모든 연결을 닫아(신규 접속은 거부, 기존 연결은 리셋) {@link #restore()}로 같은
 * 포트에서 다시 받는다. 새 의존성(Toxiproxy 등) 없이 JDK NIO 채널만 쓴다.
 */
final class TcpProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TcpProxy.class);

    private static final int BUFFER_SIZE = 8192;

    private final Supplier<InetSocketAddress> upstream;
    private final int listenPort;
    private final Set<Closeable> openChannels = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private ServerSocketChannel acceptor;

    private TcpProxy(Supplier<InetSocketAddress> upstream) throws IOException {
        this.upstream = upstream;
        this.acceptor = bind(0);
        this.listenPort = ((InetSocketAddress) acceptor.getLocalAddress()).getPort();
        acceptInBackground(acceptor);
    }

    /**
     * 빈 포트에서 중계를 시작한다. 업스트림 주소는 접속이 들어올 때마다 조회하므로 컨테이너 기동 이전에 만들어 둘 수 있다.
     *
     * @param upstream 중계 대상(Redis) 주소 공급자
     */
    static TcpProxy listening(Supplier<InetSocketAddress> upstream) {
        try {
            return new TcpProxy(upstream);
        } catch (IOException e) {
            throw new UncheckedIOException("테스트 중계기 포트를 열지 못했다", e);
        }
    }

    /** 클라이언트가 접속할 중계기 포트 — {@link #sever()}/{@link #restore()}를 거쳐도 유지된다. */
    int port() {
        return listenPort;
    }

    /** 신규 접속을 거부하고 진행 중인 모든 연결을 끊는다 (Redis 연결 두절). */
    void sever() {
        disconnect(acceptor);
        openChannels.forEach(TcpProxy::disconnect);
        openChannels.clear();
    }

    /** 같은 포트에서 접속을 다시 받는다 (Redis 복구). */
    void restore() {
        try {
            acceptor = bind(listenPort);
        } catch (IOException e) {
            throw new UncheckedIOException("테스트 중계기 포트를 다시 열지 못했다 port=" + listenPort, e);
        }
        acceptInBackground(acceptor);
    }

    @Override
    public void close() {
        sever();
        executor.shutdownNow();
    }

    private static ServerSocketChannel bind(int port) throws IOException {
        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        channel.bind(new InetSocketAddress(port));
        return channel;
    }

    private void acceptInBackground(ServerSocketChannel channel) {
        executor.submit(
                () -> {
                    while (channel.isOpen()) {
                        try {
                            relayAsync(channel.accept());
                        } catch (IOException e) {
                            return; // sever()가 리스닝 채널을 닫으면 accept가 여기로 빠진다.
                        }
                    }
                });
    }

    private void relayAsync(SocketChannel client) {
        openChannels.add(client);
        executor.submit(() -> relay(client));
    }

    /** 클라이언트 접속 1건을 업스트림에 이어 양방향으로 중계한다. 한쪽 방향이 끝나면 양쪽 채널이 닫힌다. */
    private void relay(SocketChannel client) {
        try (SocketChannel server = SocketChannel.open(upstream.get())) {
            openChannels.add(server);
            executor.submit(() -> pipe(server, client));
            pipe(client, server);
        } catch (IOException e) {
            disconnect(client);
        }
    }

    /** {@code from}에서 읽은 바이트를 {@code to}로 옮긴다. 어느 쪽이든 끊기면 양쪽을 닫는다. */
    private void pipe(SocketChannel from, SocketChannel to) {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            while (from.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    to.write(buffer);
                }
                buffer.clear();
            }
        } catch (IOException e) {
            logIgnored("중계 종료(두절·종료는 정상 경로다)", e);
        } finally {
            disconnect(from);
            disconnect(to);
        }
    }

    private static void disconnect(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            logIgnored("닫는 중 오류(이미 끊긴 채널이다)", e);
        }
    }

    private static void logIgnored(String what, IOException cause) {
        if (log.isDebugEnabled()) {
            log.debug("[tcp-proxy] {}", what, cause);
        }
    }
}
