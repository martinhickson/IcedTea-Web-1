package net.sourceforge.jnlp.cache;

import java.net.URL;

import javax.jnlp.DownloadServiceListener;

public class NullDownloadServiceListener implements DownloadServiceListener {

    @Override
    public void progress(URL url, String version, long readSoFar, long total, int overallPercent) {
    }

    @Override
    public void validating(URL url, String version, long entry, long total, int overallPercent) {
    }

    @Override
    public void upgradingArchive(URL url, String version, int patchPercent, int overallPercent) {
    }

    @Override
    public void downloadFailed(URL url, String version) {
    }

}
