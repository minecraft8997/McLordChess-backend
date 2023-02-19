package ru.deewend.chessserver;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Helper {
    public interface Providable<T> {
        boolean provide(T object) throws Exception;
    }

    public static final boolean USE_PLATFORM_THREADS;
    private static final DateFormat FORMAT =
            new SimpleDateFormat("[HH:mm:ss dd.MM.yyyy] ");

    static {
        USE_PLATFORM_THREADS = "true".equalsIgnoreCase(
                System.getProperty("chessserver.usePlatformThreads"));
    }

    private Helper() {
    }

    public static synchronized void log(String message) {
        System.out.println(FORMAT.format(new Date()) + message);
    }

    public static void newThread(String name, Runnable task) {
        if (USE_PLATFORM_THREADS) {
            Thread thread = new Thread(task);
            thread.setName(name);
            thread.setDaemon(true);
            thread.start();
        } else {
            try {
                Method ofVirtual = Thread.class.getDeclaredMethod("ofVirtual");
                ofVirtual.setAccessible(true);
                Object virtualBuilder = ofVirtual.invoke(null);
                Method nameSet = virtualBuilder.getClass()
                        .getDeclaredMethod("name", String.class);
                nameSet.setAccessible(true);
                virtualBuilder = nameSet.invoke(virtualBuilder, name);
                Method start = virtualBuilder.getClass()
                        .getDeclaredMethod("start", Runnable.class);
                start.setAccessible(true);
                start.invoke(virtualBuilder, task);
            } catch (Exception e) {
                System.err.println("Failed to start a virtual thread (name=\"" + name + "\")");
                System.err.println("Are you running Java 19 (with enabled preview " +
                        "features) or later?");
                System.err.println("If you wish to use classic platform threads, " +
                        "please re-run the server with the following JVM flag: " +
                        "-Dchessserver.usePlatformThreads=true");
                System.err.println("If you wish to enable preview features, please " +
                        "re-run the server with the following JVM flag: --enable-preview");
                System.err.println("Also, for virtual threads support, please make sure " +
                        "you include this flag as well: " +
                        "--add-opens java.base/java.lang=ALL-UNNAMED");
                System.err.println("The exception will be wrapped into a RuntimeException");

                throw new RuntimeException(e);
            }
        }
    }

    public static boolean checkInvitationCode(String code) {
        if (code.length() != 4) return false;

        for (int i = 0; i < code.length(); i++) {
            char currentChar = code.charAt(i);
            if (currentChar >= 'a' && currentChar <= 'f') continue;
            if (currentChar >= '0' && currentChar <= '9') continue;

            return false;
        }

        return true;
    }

    public static void sendMessageIgnoreErrors(ClientHandler handler, String message) {
        try {
            handler.sendMessage(message);
        } catch (Throwable ignored) {}
    }

    public static byte[] constructCachedPacket(Providable<DataOutputStream> constructor) {
        try (ByteArrayOutputStream byteStream0 = new ByteArrayOutputStream()) {
            DataOutputStream byteStream = new DataOutputStream(byteStream0);

            constructor.provide(byteStream);

            return byteStream0.toByteArray();
        } catch (Exception e) { // should never happen tho
            throw new RuntimeException(e);
        }
    }
}
