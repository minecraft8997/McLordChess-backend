package ru.deewend.chessserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.*;

public class ChessServer {
    // do not change these fields
    public static final int TICK_RATE_HZ = 20;
    public static final int MAX_SLEEP_TIME_MS = 1000 / TICK_RATE_HZ;
    public static final int SERVER_PORT;
    public static final int MAX_ONLINE_PLAYER_COUNT;
    public static final int MAX_ONLINE_PLAYER_COUNT_SOFT_KICK;
    public static final int MAX_ROOM_COUNT;
    public static final int PLAYER_TIME_S;
    public static final int PLAYER_TIME_TICKS;
    public static final int MAX_HOST_WAITING_TIME_S;
    public static final int MAX_HOST_WAITING_TIME_TICKS;
    public static final byte ACTION_ACCEPT = 0;
    public static final byte ACTION_AND_CLOSE_LATER = 1;
    public static final byte ACTION_CLOSE_NOW = 2;

    private final Random random = new SecureRandom();
    private volatile int onlinePlayerCount;
    private final Map<String, GameRoom> gameRooms = new HashMap<>();

    static {
        Helper.log("Initializing...");
        SERVER_PORT = Integer.parseInt(
                System.getProperty("chessserver.port", "5557"));
        MAX_ONLINE_PLAYER_COUNT = Integer.parseInt(
                System.getProperty("chessserver.maxOnlinePlayerCount", "1500"));
        MAX_ONLINE_PLAYER_COUNT_SOFT_KICK = Integer.parseInt(
                System.getProperty("chessserver.maxOnlinePlayerCountSoftKick", "2000"));
        MAX_ROOM_COUNT = Integer.parseInt(
                System.getProperty("chessserver.maxRoomCount", "700"));
        PLAYER_TIME_S = Integer.parseInt(
                System.getProperty("chessserver.playerTimeSeconds", "1800"));
        MAX_HOST_WAITING_TIME_S = Integer.parseInt(
                System.getProperty("chessserver.maxHostWaitingTimeSeconds", "900"));

        PLAYER_TIME_TICKS = PLAYER_TIME_S * TICK_RATE_HZ;
        MAX_HOST_WAITING_TIME_TICKS = MAX_HOST_WAITING_TIME_S * TICK_RATE_HZ;
    }

    public static void main(String[] args) throws Throwable {
        (new ChessServer()).run();
    }

    @SuppressWarnings("InfiniteLoopStatement")
    public void run() throws IOException {
        Helper.newThread("Watchdog", (new Runnable() {
            private final List<String> entriesToRemove = new ArrayList<>();

            @Override
            @SuppressWarnings({"BusyWait", "finally"})
            public void run() {
                try {
                    Thread.sleep(MAX_SLEEP_TIME_MS);
                    while (true) {
                        long started = System.currentTimeMillis();
                        tick();
                        long delta = System.currentTimeMillis() - started;
                        if (delta < MAX_SLEEP_TIME_MS) {
                            Thread.sleep(MAX_SLEEP_TIME_MS - delta);
                        } else {
                            Thread.sleep(1);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    e.printStackTrace();
                } finally {
                    System.err.println("The WatchDog thread has died. The application can't " +
                            "continue operate normally, thus the server will be terminated");

                    System.exit(-1);
                }
            }

            @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
            private void tick() {
                accessGameRooms(gameRooms -> {
                    if (gameRooms.isEmpty()) return true;

                    for (Map.Entry<String, GameRoom> entry : gameRooms.entrySet()) {
                        GameRoom gameRoom = entry.getValue();

                        // at this moment no one can connect to this room
                        // (since the map is locked), so we are free to
                        // perform our job without synchronization
                        if (gameRoom.getOpponentPlayerHandler() == null) {
                            if (gameRoom.getWaitingForTheOpponentTicks() >=
                                    MAX_HOST_WAITING_TIME_TICKS
                            ) {
                                // IOExceptions are ignored
                                Helper.sendMessageIgnoreErrors(
                                        gameRoom.getHostPlayerHandler(),
                                        "disconnect:host_timeout"
                                );
                                gameRoom.getHostPlayerHandler().close();
                                entriesToRemove.add(entry.getKey());
                            } else {
                                gameRoom.incrementWaitingForTheOpponentTime();
                            }

                            continue;
                        }

                        if (gameRoom.getHostPlayerHandler().isClosed() ||
                                gameRoom.getOpponentPlayerHandler().isClosed()
                        ) {
                            continue; // the game will be terminated by their own threads
                        }

                        synchronized (gameRoom) {
                            if (gameRoom.getWhoMakesAMove() == gameRoom.getHostPlayerHandler()) {
                                gameRoom.decrementHostPlayerRemainingTime();
                            } else {
                                gameRoom.decrementOpponentPlayerRemainingTime();
                            }

                            int hostRemaining = gameRoom.getHostPlayerRemainingTimeTicks();
                            int opponentRemaining = gameRoom
                                    .getOpponentPlayerRemainingTimeTicks();

                            gameRoom.incrementTicksExists();
                            if (gameRoom.getTicksExists() % 100 == 0) {
                                gameRoom.sendAll("time_sync " +
                                        hostRemaining + " " + opponentRemaining);
                            }
                            if (hostRemaining <= 0 || opponentRemaining <= 0) {
                                entriesToRemove.add(entry.getKey());

                                if (hostRemaining <= 0 && opponentRemaining <= 0) {
                                    gameRoom.sendAll("disconnect:timed_out_draw");
                                    gameRoom.getHostPlayerHandler().close();
                                    // opponent's handler will be closed as well

                                    continue;
                                }

                                String message;
                                if (hostRemaining <= 0) {
                                    message = "disconnect:timed_out_" +
                                            (gameRoom.getHostColor() ? "white" : "black");
                                } else {
                                    message = "disconnect:timed_out_" +
                                            (gameRoom.getHostColor() ? "black" : "white");
                                }
                                gameRoom.sendAll(message);
                                gameRoom.getHostPlayerHandler().close();
                            }
                        }
                    }
                    if (!entriesToRemove.isEmpty()) {
                        for (String key : entriesToRemove) { // key = invitationCode
                            gameRooms.remove(key);
                        }
                        entriesToRemove.clear();
                    }

                    return true;
                });
            }
        }));

        ServerSocket listeningSocket = new ServerSocket(SERVER_PORT);
        Helper.log("Completed. The server is listening on port " + SERVER_PORT);

        while (true) {
            Socket socket = listeningSocket.accept();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(PLAYER_TIME_S * 1000 + (5 * 60 * 1000)); // adding 5 minutes

            byte action;
            synchronized (this) {
                int onlinePlayerCount = ++this.onlinePlayerCount;
                if (onlinePlayerCount <= MAX_ONLINE_PLAYER_COUNT)
                    action = ACTION_ACCEPT;
                else if (onlinePlayerCount <= MAX_ONLINE_PLAYER_COUNT_SOFT_KICK)
                    action = ACTION_AND_CLOSE_LATER;
                else
                    action = ACTION_CLOSE_NOW;
            }

            ClientHandler handler;
            if (action == ACTION_ACCEPT) {
                handler = new ClientHandler(this, socket);
            } else if (action == ACTION_AND_CLOSE_LATER) {
                handler = new ClientHandler(this, socket, true);
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {}

                continue;
            }

            Helper.newThread("Client Handler", handler);
        }
    }

    public Random getRandom() {
        return random;
    }

    public int getOnlinePlayerCount() {
        return onlinePlayerCount;
    }

    public synchronized void decrementOnlinePlayerCount() {
        onlinePlayerCount--;
    }

    public boolean accessGameRooms(Helper.Providable<Map<String, GameRoom>> providable) {
        synchronized (gameRooms) {
            try {
                return providable.provide(gameRooms);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
