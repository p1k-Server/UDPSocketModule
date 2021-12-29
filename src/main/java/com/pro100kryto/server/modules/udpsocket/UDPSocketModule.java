package com.pro100kryto.server.modules.udpsocket;

import com.pro100kryto.server.livecycle.AShortLiveCycleImpl;
import com.pro100kryto.server.livecycle.ILiveCycleImpl;
import com.pro100kryto.server.module.AModule;
import com.pro100kryto.server.module.IModuleConnectionSafe;
import com.pro100kryto.server.module.ModuleParams;
import com.pro100kryto.server.modules.packetpool.connection.IPacketPoolModuleConnection;
import com.pro100kryto.server.modules.udpsocket.connection.ISocketListener;
import com.pro100kryto.server.modules.udpsocket.connection.IUDPSocketModuleConnection;
import com.pro100kryto.server.settings.IntegerSettingListener;
import com.pro100kryto.server.settings.SettingListenerContainer;
import com.pro100kryto.server.settings.SettingListenerEventMask;
import com.pro100kryto.server.tick.AModuleTickRunnable;
import com.pro100kryto.server.tick.ITick;
import com.pro100kryto.server.tick.ITickGroup;
import com.pro100kryto.server.tick.Ticks;
import com.pro100kryto.server.utils.datagram.packet.EndPoint;
import com.pro100kryto.server.utils.datagram.packet.Packet;
import com.pro100kryto.server.utils.datagram.packet.PacketStatus;
import org.jetbrains.annotations.NotNull;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class UDPSocketModule extends AModule<IUDPSocketModuleConnection> {
    private IModuleConnectionSafe<IPacketPoolModuleConnection> packetPoolModuleConnection;

    private BlockingQueue<Packet> packetBuffer;

    private DatagramSocket socket;
    private ITickGroup tickGroup;
    private ITick tick;

    private ISocketListener listener;

    public UDPSocketModule(ModuleParams moduleParams) {
        super(moduleParams);
    }

    @Override
    public @NotNull IUDPSocketModuleConnection createModuleConnection() {
        return new UDPSocketModuleConnection(this, logger);
    }

    @Override
    protected @NotNull ILiveCycleImpl getDefaultLiveCycleImpl() {
        return new UDPSocketLiveCycleImpl(this);
    }

    public void setListener(ISocketListener listener) {
        this.listener = listener;
    }

    public BlockingQueue<Packet> getPacketBuffer() {
        return packetBuffer;
    }

    public DatagramSocket getSocket() {
        return socket;
    }

    private final class UDPSocketLiveCycleImpl extends AShortLiveCycleImpl {
        private final UDPSocketModule module;

        private UDPSocketLiveCycleImpl(UDPSocketModule module) {
            this.module = module;
        }

        @Override
        public void init() throws Throwable {
            packetPoolModuleConnection = setupModuleConnectionSafe(
                    settings.getOrDefault("connection-PacketPoolModule", "packetPool")
            );

            socket = new DatagramSocket(
                    settings.getIntOrDefault("socket-port", 0)
            );
            logger.info("Socket port is "+socket.getLocalPort());

            packetBuffer = new ArrayBlockingQueue<>(
                    settings.getIntOrDefault("buffer-size", 64)
            );

            settingsManager.setListener(new SettingListenerContainer(
                    "socket-timeout",
                    new IntegerSettingListener() {
                        @Override
                        public void apply2(String key, Integer val, SettingListenerEventMask eventMask) throws Throwable {
                             socket.setSoTimeout(val);
                        }
                    },
                    5000
            ));

            tickGroup = Ticks.newTickGroupPreMod(settings);
            tickGroup.getLiveCycle().init();

            tick = tickGroup.createTick(new AModuleTickRunnable<UDPSocketModule>(module, logger) {
                @Override
                public void tick(long dtms) throws Throwable {
                    final Packet packet = packetPoolModuleConnection.getModuleConnection().getNextPacket();

                    try {
                        final DatagramPacket datagramPacket = packet.asDatagramPacket();
                        socket.receive(datagramPacket);
                        packet.setEndPoint(new EndPoint(datagramPacket.getAddress(), datagramPacket.getPort()));
                        packet.setPacketStatus(PacketStatus.Received);

                        if (listener != null) {
                            listener.process(packet);
                        } else {
                            if (!packetBuffer.offer(packet)) {
                                logger.warn("packetBuffer is full");
                            }
                        }

                    } catch (SocketTimeoutException socketTimeoutException) {
                        logger.info("SocketTimeoutException : " + socketTimeoutException.getMessage());
                        packet.recycle();

                    } catch (Exception e) {
                        packet.recycle();
                        throw e;
                    }
                }
            });
            tick.init();
        }

        @Override
        public void start() throws Throwable {
            if (socket.isClosed()){
                throw new IllegalStateException("Socket is closed");
            }

            tick.activate();
            tickGroup.getLiveCycle().start();
        }

        @Override
        public void stopForce() {
            tickGroup.getLiveCycle().stopForce();
            tick.inactivate();
        }

        @Override
        public void destroy() {
            socket.close();
            socket = null;

            packetBuffer = null;

            tick.destroy();
            tickGroup.getLiveCycle().destroy();

            packetPoolModuleConnection.close();
        }
    }
}
