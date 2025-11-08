package org.apache.log4j;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;

public class Level extends Priority {
    public static final int X_TRACE_INT = 9900;
    public static final Level OFF = new Level(Integer.MAX_VALUE, "OFF", 0);
    public static final Level FATAL = new Level(50000, "FATAL", 0);
    public static final Level ERROR = new Level(40000, "ERROR", 3);
    public static final Level WARN = new Level(30000, "WARN", 4);
    public static final Level INFO = new Level(20000, "INFO", 6);
    public static final Level DEBUG = new Level(10000, "DEBUG", 7);
    public static final Level TRACE = new Level(5000, "TRACE", 7);
    public static final Level ALL = new Level(Integer.MIN_VALUE, "ALL", 7);

    protected Level(int level, String levelStr, int syslogEquivalent) {
        super(level, levelStr, syslogEquivalent);
    }

    public static Level toLevel(String sArg) {
        return toLevel(sArg, DEBUG);
    }

    public static Level toLevel(int val) {
        return toLevel(val, DEBUG);
    }

    public static Level toLevel(int val, Level defaultLevel) {
        switch (val) {
            case Integer.MIN_VALUE:
                return ALL;
            case 5000:
                return TRACE;
            case 10000:
                return DEBUG;
            case 20000:
                return INFO;
            case 30000:
                return WARN;
            case 40000:
                return ERROR;
            case 50000:
                return FATAL;
            case Integer.MAX_VALUE:
                return OFF;
            default:
                return defaultLevel;
        }
    }

    public static Level toLevel(String sArg, Level defaultLevel) {
        if (sArg == null) {
            return defaultLevel;
        } else {
            String s = sArg.toUpperCase();
            if (s.equals("ALL")) {
                return ALL;
            } else if (s.equals("DEBUG")) {
                return DEBUG;
            } else if (s.equals("INFO")) {
                return INFO;
            } else if (s.equals("WARN")) {
                return WARN;
            } else if (s.equals("ERROR")) {
                return ERROR;
            } else if (s.equals("FATAL")) {
                return FATAL;
            } else if (s.equals("OFF")) {
                return OFF;
            } else {
                return s.equals("TRACE") ? TRACE : defaultLevel;
            }
        }
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
        s.defaultReadObject();
        this.level = s.readInt();
        this.syslogEquivalent = s.readInt();
        this.levelStr = s.readUTF();
        if (this.levelStr == null) {
            this.levelStr = "";
        }

    }

    private void writeObject(ObjectOutputStream s) throws IOException {
        s.defaultWriteObject();
        s.writeInt(this.level);
        s.writeInt(this.syslogEquivalent);
        s.writeUTF(this.levelStr);
    }

    private Object readResolve() throws ObjectStreamException {
        return this.getClass() == Level.class ? toLevel(this.level) : this;
    }
}
