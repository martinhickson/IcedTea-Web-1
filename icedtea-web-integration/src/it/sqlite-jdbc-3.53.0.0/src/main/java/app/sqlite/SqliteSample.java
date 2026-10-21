package app.sqlite;

import org.sqlite.SQLiteConfig;

/**
 * Sample application compiled against sqlite-jdbc 3.53.0.0.
 * {@code SQLiteConfig.setWalAutocheckpoint(int)} was added in 3.53.0.0 and is not on 3.46.1.3.
 */
public final class SqliteSample {

    public static void main(String[] args) {
        SQLiteConfig config = new SQLiteConfig();
        config.setWalAutocheckpoint(1000);
        String checkpoint = config.toProperties().getProperty("wal_autocheckpoint");
        if (!"1000".equals(checkpoint)) {
            throw new IllegalStateException("wal_autocheckpoint was " + checkpoint);
        }
        System.out.println("ITW_LIBRARY_SAMPLE_OK sqlite wal_autocheckpoint=" + checkpoint);
    }
}
