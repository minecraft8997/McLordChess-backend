package ru.deewend.chessserver;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientHandler implements Runnable {
    private final ChessServer chessServer;
    private final Socket socket;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;
    private final boolean closeBecauseOfOverload;
    private boolean host;
    private GameRoom gameRoom;

    public ClientHandler(ChessServer chessServer, Socket socket) {
        this(chessServer, socket, false);
    }

    public ClientHandler(
            ChessServer chessServer, Socket socket, boolean closeBecauseOfOverload
    ) {
        this.chessServer = chessServer;
        this.socket = socket;
        this.closeBecauseOfOverload = closeBecauseOfOverload;
    }

    @Override
    public void run() {
        try (Socket ignored = this.socket) {
            run0();
        } catch (Throwable ignored) {
        } finally {
            chessServer.decrementOnlinePlayerCount();

            if (gameRoom != null) {
                ClientHandler handler;
                if (host) handler = gameRoom.getOpponentPlayerHandler();
                else      handler = gameRoom.getHostPlayerHandler();

                if (handler != null && !handler.isClosed()) {
                    Helper.sendMessageIgnoreErrors(handler,
                            "disconnect:opponent_disconnected");
                    handler.close();
                }

                chessServer.accessGameRooms(gameRooms -> {
                    gameRooms.remove(gameRoom.getInvitationCode()); return true;
                });
            }
        }
    }

    @SuppressWarnings("SynchronizeOnNonFinalField")
    private void run0() throws Throwable {
        if (!websocketInit()) return;
        if (closeBecauseOfOverload) {
            sendMessage("disconnect:overloaded"); return;
        }

        String[] initialMessage = receiveMessage();
        if (!validateInitialMessage(initialMessage)) {
            sendMessage("disconnect:protocol_error"); return;
        }

        String action = initialMessage[0];
        //noinspection AssignmentUsedAsCondition
        if ((host = action.equals("mclord_host"))) { // please note an assignment
            boolean successfullyHosted = chessServer.accessGameRooms(gameRooms -> {
                if (gameRooms.size() >= ChessServer.MAX_ROOM_COUNT) {
                    sendMessage("disconnect:overloaded"); return false;
                }

                boolean succeed = false;
                String invitationCode = null;
                for (int i = 0; i < 5; i++) {
                    GameRoom gameRoom = new GameRoom(chessServer, this);
                    if (gameRooms.containsKey(gameRoom.getInvitationCode())) continue;

                    this.gameRoom = gameRoom;
                    gameRooms.put((invitationCode = gameRoom.getInvitationCode()), gameRoom);
                    succeed = true; break;
                }
                if (!succeed) { // Whoa...
                    sendMessage("disconnect:ooooh_i_am_giving_it_up");
                } else {
                    sendMessage("host_ok " + invitationCode);
                }

                return succeed;
            });

            if (!successfullyHosted) return;
        } else if (action.equals("mclord_quick_stats")) {
            int[] data = new int[2];
            data[0] = chessServer.getOnlinePlayerCount();
            chessServer.accessGameRooms(gameRooms -> {
                data[1] = gameRooms.size(); return true;
            });

            sendMessage("mclord_ok " + data[0] + " " + data[1]); return;
        } else if (action.equals("mclord_connect")) {
            String invitationCode = initialMessage[1];
            boolean successfullyConnected = chessServer.accessGameRooms(gameRooms -> {
                if (!gameRooms.containsKey(invitationCode)) {
                    sendMessage("disconnect:invalid_code"); return false;
                }
                GameRoom gameRoom = gameRooms.get(invitationCode);
                if (!gameRoom.connectSecond(this)) {
                    sendMessage("disconnect:already_in_game"); return false;
                }
                Helper.log("A new game has started!");

                this.gameRoom = gameRoom; return true;
            });
            if (!successfullyConnected) return;
        } else {
            sendMessage("disconnect:protocol_error"); return;
        }

        while (true) {
            String[] gameMessage = receiveMessage();
            if (gameMessage.length != 2 ||
                    (!gameMessage[0].equals("san") && !gameMessage[0].equals("resign"))
            ) {
                sendMessage("disconnect:protocol_error"); return;
            }

            if (gameMessage[0].equals("resign")) {
                synchronized (gameRoom) {
                    Helper.sendMessageIgnoreErrors(gameRoom.getOpponentPlayerHandler(),
                            "disconnect:opponent_resigned");
                    gameRoom.getOpponentPlayerHandler().close();

                    sendMessage("disconnect:you_resigned");
                }

                return;
            }

            boolean finished;
            try {
                finished = gameRoom.checkAndDoMove(this, gameMessage[1]);
            } catch (IllegalArgumentException | IllegalStateException e) {
                sendMessage("disconnect:protocol_error"); return;
            } catch (RuntimeException e) {
                return;
            }
            if (finished) break;
        }
    }

    private boolean validateInitialMessage(String[] initialMessage)  {
        if (initialMessage.length == 1 &&
                !initialMessage[0].equals("mclord_host") &&
                !initialMessage[0].equals("mclord_quick_stats")
        ) {
            return false;
        }
        if (initialMessage.length == 2 &&
                (!initialMessage[0].equals("mclord_connect") ||
                        !Helper.checkInvitationCode(initialMessage[1]))
        ) {
            return false;
        }

        return initialMessage.length <= 2;
    }

    public void sendMessage(String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 125) {
            throw new IllegalArgumentException("The encoded " +
                    "message is too long for this implementation");
        }

        outputStream.write(Helper.constructCachedPacket(stream -> {
            stream.write(129);
            stream.write(bytes.length);
            stream.write(bytes);

            return true;
        })); outputStream.flush();
    }

    private String[] receiveMessage() throws IOException {
        int type = inputStream.readUnsignedByte();
        if (type != 129) throw new IOException("Unsupported message type");
        int length = inputStream.readUnsignedByte();
        length -= 128;
        if (!(length >= 0 && length <= 125)) {
            if (length == 126) {
                int high = (inputStream.readUnsignedByte() & 0x00ff);
                int low = inputStream.readUnsignedByte();

                // converting these two unsigned bytes to an unsigned short
                length = ((high & 0xFF) << 8) | (low & 0xFF);
            } else {
                // 32 bit and 64 bit lengths are unsupported to prevent DoS vulnerabilities
                throw new IOException("Unsupported length");
            }
        }
        byte[] key = new byte[4];
        if (inputStream.read(key) != key.length) {
            throw new IOException("Could not receive the key");
        }

        byte[] payload = new byte[length];
        if (inputStream.read(payload) != payload.length) {
            throw new IOException("Could not receive the payload: " + payload.length);
        }

        for (int i = 0; i < payload.length; i++) payload[i] ^= key[i & 3];

        return (new String(payload, StandardCharsets.UTF_8)).split(" ");
    }

    private boolean websocketInit() throws Exception {
        inputStream = new DataInputStream(socket.getInputStream());
        outputStream = new DataOutputStream(socket.getOutputStream());

        Scanner tmpScanner = new Scanner(inputStream, "UTF-8");
        String data = tmpScanner.useDelimiter("\\r\\n\\r\\n").next();

        Matcher getMatcher = Pattern.compile("^GET").matcher(data);
        Matcher keyMatcher = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);

        if (!getMatcher.find() || !keyMatcher.find()) {
            byte[] response = ("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);

            outputStream.write(response); outputStream.flush(); return false;
        }

        byte[] plain = (keyMatcher.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                .getBytes(StandardCharsets.UTF_8);
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(plain));

        byte[] response = ("HTTP/1.1 101 Switching Protocols\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: websocket\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8);

        outputStream.write(response); outputStream.flush(); return true;
    }

    public boolean isClosed() {
        return socket.isClosed();
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {}
    }
}
