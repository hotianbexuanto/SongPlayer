package com.github.hhhzzzsss.songplayer.song;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.conversion.MidiConverter;
import com.github.hhhzzzsss.songplayer.conversion.NBSConverter;
import com.github.hhhzzzsss.songplayer.conversion.TxtConverter;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SongLoaderThread extends Thread{

	private String location;
	private Path songPath;
	private URL songUrl;
	public Exception exception;
	public Song song;
	public String filename;

	private boolean isUrl = false;

	// 进度回调
	private ProgressListener progressListener;
	private volatile int loadingProgress = 0;
	private volatile String loadingStage = "";
	private volatile int lastReportedProgress = -1; // 上次报告的进度百分比

	public interface ProgressListener {
		void onProgress(int percentage, String stage);
	}

	public void setProgressListener(ProgressListener listener) {
		this.progressListener = listener;
	}

	public int getLoadingProgress() {
		return loadingProgress;
	}

	public String getLoadingStage() {
		return loadingStage;
	}

	protected SongLoaderThread() {}

	public SongLoaderThread(String location) throws IOException {
		this.location = location;
		if (location.startsWith("http://") || location.startsWith("https://")) {
			isUrl = true;
			songUrl = new URL(location);
		}
		else if (Files.exists(getSongFile(location))) {
			songPath = getSongFile(location);
		}
		else if (Files.exists(getSongFile(location+".mid"))) {
			songPath = getSongFile(location+".mid");
		}
		else if (Files.exists(getSongFile(location+".midi"))) {
			songPath = getSongFile(location+".midi");
		}
		else if (Files.exists(getSongFile(location+".nbs"))) {
			songPath = getSongFile(location+".nbs");
		}
		else {
			throw new IOException("Could not find song: " + location);
		}
	}

	public SongLoaderThread(Path file) {
		this.songPath = file;
	}
	
	public void run() {
		try {
			byte[] bytes;

			// 下载/读取文件阶段
			updateProgress(0, "正在加载文件...");
			if (isUrl) {
				bytes = DownloadUtils.DownloadToByteArray(songUrl, 10*1024*1024);
				filename = Paths.get(songUrl.toURI().getPath()).getFileName().toString();
			}
			else {
				bytes = Files.readAllBytes(songPath);
				filename = songPath.getFileName().toString();
			}
			updateProgress(20, "文件加载完成");

			// MIDI格式尝试
			if (song == null) {
				try {
					updateProgress(25, "尝试解析MIDI格式...");
					song = MidiConverter.getSongFromBytes(bytes, filename, (percentage, processed, total) -> {
						// 将MIDI解析进度映射到25%-70%
						int overallProgress = 25 + (percentage * 45 / 100);
						// 简化显示，不显示具体事件数（避免刷屏）
						updateProgress(overallProgress, "解析MIDI中...");
					});
					if (song != null) {
						updateProgress(70, "MIDI解析完成");
					}
				}
				catch (OutOfMemoryError e) {
					throw new IOException("内存不足！文件过大，请增加Java堆内存或使用较小的MIDI文件");
				}
				catch (Exception e) {
					updateProgress(30, "非MIDI格式");
				}
			}

			// NBS格式尝试
			if (song == null) {
				try {
					updateProgress(70, "尝试解析NBS格式...");
					song = NBSConverter.getSongFromBytes(bytes, filename);
					if (song != null) {
						updateProgress(85, "NBS解析完成");
					}
				}
				catch (Exception e) {
					updateProgress(75, "非NBS格式");
				}
			}

			// TXT格式尝试
			if (song == null) {
				try {
					updateProgress(85, "尝试解析TXT格式...");
					song = TxtConverter.getSongFromBytes(bytes, filename);
					if (song != null) {
						updateProgress(95, "TXT解析完成");
					}
				}
				catch (Exception e) {
					updateProgress(90, "非TXT格式");
				}
			}

			if (song == null) {
				throw new IOException("Invalid song format");
			}

			// 显示转换统计信息（如果有）
			if (song.conversionStats != null && !song.conversionStats.isEmpty()) {
				updateProgress(100, song.conversionStats);
			} else {
				updateProgress(100, "歌曲加载完成");
			}
		}
		catch (Exception e) {
			exception = e;
			updateProgress(-1, "加载失败: " + e.getMessage());
		}
	}

	private void updateProgress(int percentage, String stage) {
		this.loadingProgress = percentage;
		this.loadingStage = stage;

		// 只在进度百分比变化时才通知（避免刷屏）
		if (progressListener != null && percentage != lastReportedProgress) {
			lastReportedProgress = percentage;
			progressListener.onProgress(percentage, stage);
		}
	}

	private Path getSongFile(String name) throws IOException {
		return Util.resolveWithIOException(SongPlayer.SONG_DIR, name);
	}
}
