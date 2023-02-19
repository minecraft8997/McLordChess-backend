package ru.deewend.chessserver;

import com.github.bhlangonijr.chesslib.Board;

import java.io.IOException;
import java.util.Random;

public class GameRoom {
    @SuppressWarnings("FieldCanBeLocal")
    private final ChessServer chessServer;
    private final ClientHandler hostPlayerHandler;
    private final String invitationCode;
    private final boolean hostColor; // true = white, false = black
    private final Board board;
    private volatile ClientHandler opponentPlayerHandler;
    private volatile ClientHandler whoMakesAMove;
    private int waitingForTheOpponentTicks;
    private int ticksExists;
    private int hostPlayerRemainingTimeTicks = ChessServer.PLAYER_TIME_S * 20;
    private int opponentPlayerRemainingTimeTicks = ChessServer.PLAYER_TIME_S * 20;

    public GameRoom(ChessServer chessServer, ClientHandler hostPlayerHandler) {
        this.chessServer = chessServer;
        this.hostPlayerHandler = hostPlayerHandler;

        Random random = this.chessServer.getRandom();
        // generating invitation code
        byte[] b = new byte[2];
        random.nextBytes(b);
        int v1 = Byte.toUnsignedInt(b[0]);
        int v2 = Byte.toUnsignedInt(b[1]);
        String v1s = Integer.toHexString(v1);
        String v2s = Integer.toHexString(v2);

        if (v1s.length() == 1) v1s = "0" + v1s;
        if (v2s.length() == 1) v2s = "0" + v2s;
        this.invitationCode = (v1s + v2s)
                .replace("dead", String.valueOf(1000 + random.nextInt(9000)))
                .replace("666", "545"); // no scary things :3

        this.hostColor = random.nextBoolean();
        this.board = new Board();

        if (this.hostColor) {
            this.whoMakesAMove = hostPlayerHandler;
        }
    }

    public synchronized boolean checkAndDoMove(ClientHandler handler, String san) {
        if (opponentPlayerHandler == null) throw new IllegalStateException();
        if (board.isMated()) throw new IllegalStateException();
        if (handler != whoMakesAMove) throw new IllegalArgumentException();

        if (!board.doMove(san)) {
            throw new IllegalArgumentException();
        }

        ClientHandler receiver = (whoMakesAMove ==
                hostPlayerHandler ? opponentPlayerHandler : hostPlayerHandler);
        Helper.sendMessageIgnoreErrors(receiver, "san " + san);

        whoMakesAMove = receiver;
        if (board.isMated()) {
            Helper.sendMessageIgnoreErrors(handler, "disconnect:you_won");
            handler.close();
            Helper.sendMessageIgnoreErrors(receiver, "disconnect:you_lost");
            receiver.close();

            return true; // the game has been finished
        }

        return false;
    }

    public synchronized boolean connectSecond(ClientHandler second) throws IOException {
        if (this.opponentPlayerHandler != null) return false;

        if (!hostColor) {
            whoMakesAMove = second;
        }
        this.opponentPlayerHandler = second;

        if (hostColor) {
            hostPlayerHandler.sendMessage("ok_starting white");
            second.sendMessage("ok_starting black");
        } else {
            hostPlayerHandler.sendMessage("ok_starting black");
            second.sendMessage("ok_starting white");
        }

        return true;
    }

    public void sendAll(String message) {
        Helper.sendMessageIgnoreErrors(hostPlayerHandler, message);
        if (opponentPlayerHandler != null)
            Helper.sendMessageIgnoreErrors(opponentPlayerHandler, message);
    }

    public ClientHandler getHostPlayerHandler() {
        return hostPlayerHandler;
    }

    public ClientHandler getOpponentPlayerHandler() {
        return opponentPlayerHandler;
    }

    public ClientHandler getWhoMakesAMove() {
        return whoMakesAMove;
    }

    public String getInvitationCode() {
        return invitationCode;
    }

    public boolean getHostColor() {
        return hostColor;
    }

    public int getWaitingForTheOpponentTicks() {
        return waitingForTheOpponentTicks;
    }

    public void incrementWaitingForTheOpponentTime() {
        waitingForTheOpponentTicks++;
    }

    public int getTicksExists() {
        return ticksExists;
    }

    public void incrementTicksExists() {
        ticksExists++;
    }

    public int getHostPlayerRemainingTimeTicks() {
        return hostPlayerRemainingTimeTicks;
    }

    public void decrementHostPlayerRemainingTime() {
        hostPlayerRemainingTimeTicks--;
    }

    public int getOpponentPlayerRemainingTimeTicks() {
        return opponentPlayerRemainingTimeTicks;
    }

    public void decrementOpponentPlayerRemainingTime() {
        opponentPlayerRemainingTimeTicks--;
    }
}
