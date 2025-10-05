package com.github.hhhzzzsss.songplayer.conversion;

import com.github.hhhzzzsss.songplayer.song.DownloadUtils;
import com.github.hhhzzzsss.songplayer.song.Instrument;
import com.github.hhhzzzsss.songplayer.song.Note;
import com.github.hhhzzzsss.songplayer.song.Song;

import javax.sound.midi.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class MidiConverter {

	public interface ProgressCallback {
		void onProgress(int percentage, int processed, int total);
	}

	public static final int SET_INSTRUMENT = 0xC0;
	public static final int SET_TEMPO = 0x51;
	public static final int NOTE_ON = 0x90;
    public static final int NOTE_OFF = 0x80;

	public static Song getSongFromUrl(URL url) throws IOException, InvalidMidiDataException, URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
		Sequence sequence = MidiSystem.getSequence(DownloadUtils.DownloadToInputStream(url, 5*1024*1024));
		return getSong(sequence, Paths.get(url.toURI().getPath()).getFileName().toString());
	}

	public static Song getSongFromFile(Path file) throws InvalidMidiDataException, IOException {
		Sequence sequence = MidiSystem.getSequence(file.toFile());
		return getSong(sequence, file.getFileName().toString());
	}

	public static Song getSongFromBytes(byte[] bytes, String name) throws InvalidMidiDataException, IOException {
		Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(bytes));
		return getSong(sequence, name);
	}

	public static Song getSongFromBytes(byte[] bytes, String name, ProgressCallback progressCallback) throws InvalidMidiDataException, IOException {
		Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(bytes));
		return getSong(sequence, name, progressCallback);
	}
    
	public static Song getSong(Sequence sequence, String name) {
		return getSong(sequence, name, null);
	}

	public static Song getSong(Sequence sequence, String name, ProgressCallback progressCallback) {
		Song song  = new Song(name);

		long tpq = sequence.getResolution();

		// 收集所有tempo事件
		ArrayList<MidiEvent> tempoEvents = new ArrayList<>();
		for (Track track : sequence.getTracks()) {
			for (int i = 0; i < track.size(); i++) {
				MidiEvent event = track.get(i);
				MidiMessage message = event.getMessage();
				if (message instanceof MetaMessage) {
					MetaMessage mm = (MetaMessage) message;
					if (mm.getType() == SET_TEMPO) {
						tempoEvents.add(event);
					}
				}
			}
		}

		Collections.sort(tempoEvents, (a, b) -> Long.compare(a.getTick(), b.getTick()));

		// 计算总事件数用于进度显示
		int totalEvents = 0;
		for (Track track : sequence.getTracks()) {
			totalEvents += track.size();
		}
		int processedEvents = 0;

		// 统计信息
		int totalNotes = 0;
		int convertedNotes = 0;
		int skippedNotes = 0;

		// 使用HashMap存储每个轨道和通道的乐器ID，去除16通道限制
		HashMap<String, Integer> instrumentIds = new HashMap<>();

		int trackIndex = 0;
		for (Track track : sequence.getTracks()) {
			long microTime = 0;
			int mpq = 500000;
			int tempoEventIdx = 0;
			long prevTick = 0;

			for (int i = 0; i < track.size(); i++) {
				MidiEvent event = track.get(i);
				MidiMessage message = event.getMessage();

				// 更新tempo
				while (tempoEventIdx < tempoEvents.size() && event.getTick() > tempoEvents.get(tempoEventIdx).getTick()) {
					long deltaTick = tempoEvents.get(tempoEventIdx).getTick() - prevTick;
					prevTick = tempoEvents.get(tempoEventIdx).getTick();
					microTime += (mpq/tpq) * deltaTick;

					MetaMessage mm = (MetaMessage) tempoEvents.get(tempoEventIdx).getMessage();
					byte[] data = mm.getData();
					int new_mpq = (data[2]&0xFF) | ((data[1]&0xFF)<<8) | ((data[0]&0xFF)<<16);
					if (new_mpq != 0) mpq = new_mpq;
					tempoEventIdx++;
				}

				if (message instanceof ShortMessage) {
					ShortMessage sm = (ShortMessage) message;
					String channelKey = trackIndex + "_" + sm.getChannel();

					if (sm.getCommand() == SET_INSTRUMENT) {
						instrumentIds.put(channelKey, sm.getData1());
					}
					else if (sm.getCommand() == NOTE_ON) {
						int pitch = sm.getData1();
						int velocity = sm.getData2();

						// 先计入总音符数
						totalNotes++;

						// 忽略velocity为0的音符（在MIDI中velocity=0相当于NOTE_OFF）
						if (velocity == 0) {
							skippedNotes++;
							continue;
						}

						// 转换velocity: MIDI范围0-127 -> Minecraft范围0-100
						velocity = (velocity * 100) / 127;

						// 如果转换后velocity为0，也跳过（没有声音）
						if (velocity == 0) {
							skippedNotes++;
							continue;
						}

						long deltaTick = event.getTick() - prevTick;
						prevTick = event.getTick();
						microTime += (mpq/tpq) * deltaTick;

						Note note = null;
						if (sm.getChannel() == 9) {
							// 打击乐通道
							note = getMidiPercussionNote(pitch, velocity, microTime);
						}
						else {
							// 旋律乐器通道
							int instrumentId = instrumentIds.getOrDefault(channelKey, 0);
							note = getMidiInstrumentNote(instrumentId, pitch, velocity, microTime);
						}

						// 只添加成功转换的音符
						if (note != null) {
							song.add(note);
							convertedNotes++;
						} else {
							// 无法转换的音符（超出Minecraft音符盒范围或无对应乐器）
							skippedNotes++;
						}

						long time = microTime / 1000L;
						if (time > song.length) {
							song.length = time;
						}
					}
					else if (sm.getCommand() == NOTE_OFF) {
						long deltaTick = event.getTick() - prevTick;
						prevTick = event.getTick();
						microTime += (mpq/tpq) * deltaTick;
						long time = microTime / 1000L;
						if (time > song.length) {
							song.length = time;
						}
					}
				}

				// 更新进度
				processedEvents++;
				if (progressCallback != null && processedEvents % 100 == 0) {
					int progress = (int) ((processedEvents * 100.0) / totalEvents);
					progressCallback.onProgress(progress, processedEvents, totalEvents);

					// 定期让出CPU时间，避免阻塞游戏主线程
					if (processedEvents % 1000 == 0) {
						Thread.yield();
					}
				}
			}

			trackIndex++;
		}

		// 最终进度更新，包含统计信息
		if (progressCallback != null) {
			progressCallback.onProgress(100, totalEvents, totalEvents);
		}

		// 存储转换统计信息到Song对象（如果需要）
		song.conversionStats = String.format("总音符: %d, 已转换: %d, 已跳过: %d (%.1f%%)",
			totalNotes, convertedNotes, skippedNotes,
			totalNotes > 0 ? (skippedNotes * 100.0 / totalNotes) : 0);

		song.sort();

		// Shift to beginning if delay is too long
		if (!song.notes.isEmpty()) {
			long shift = song.notes.get(0).time - 1000;
			if (song.notes.get(0).time > 1000) {
				for (Note note : song.notes) {
					note.time -= shift;
				}
			}
			song.length -= shift;
		}
		
		return song;
	}

	public static Note getMidiInstrumentNote(int midiInstrument, int midiPitch, int velocity, long microTime) {
		com.github.hhhzzzsss.songplayer.song.Instrument instrument = null;
		com.github.hhhzzzsss.songplayer.song.Instrument[] instrumentList = instrumentMap.get(midiInstrument);
		if (instrumentList != null) {
			for (com.github.hhhzzzsss.songplayer.song.Instrument candidateInstrument : instrumentList) {
				if (midiPitch >= candidateInstrument.offset && midiPitch <= candidateInstrument.offset+24) {
					instrument = candidateInstrument;
					break;
				}
			}
		}

		if (instrument == null) {
			return null;
		}

		int pitch = midiPitch-instrument.offset;
		int noteId = pitch + instrument.instrumentId*25;
		long time = microTime / 1000L;

		return new Note(noteId, time, velocity);
	}

	private static Note getMidiPercussionNote(int midiPitch, int velocity, long microTime) {
		if (percussionMap.containsKey(midiPitch)) {
			int noteId = percussionMap.get(midiPitch);
			long time = microTime / 1000L;

			return new Note(noteId, time, velocity);
		}
		return null;
	}

	public static HashMap<Integer, com.github.hhhzzzsss.songplayer.song.Instrument[]> instrumentMap = new HashMap<>();
	static {
		// Piano (HARP BASS BELL)
		instrumentMap.put(0, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Acoustic Grand Piano
		instrumentMap.put(1, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Bright Acoustic Piano
		instrumentMap.put(2, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Electric Grand Piano
		instrumentMap.put(3, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Honky-tonk Piano
		instrumentMap.put(4, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Electric Piano 1
		instrumentMap.put(5, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Electric Piano 2
		instrumentMap.put(6, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Harpsichord
		instrumentMap.put(7, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Clavinet

		// Chromatic Percussion (IRON_XYLOPHONE XYLOPHONE BASS)
		instrumentMap.put(8, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Celesta
		instrumentMap.put(9, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Glockenspiel
		instrumentMap.put(10, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Music Box
		instrumentMap.put(11, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Vibraphone
		instrumentMap.put(12, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Marimba
		instrumentMap.put(13, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Xylophone
		instrumentMap.put(14, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Tubular Bells
		instrumentMap.put(15, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Dulcimer

		// Organ (BIT DIDGERIDOO BELL)
		instrumentMap.put(16, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Drawbar Organ
		instrumentMap.put(17, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Percussive Organ
		instrumentMap.put(18, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Rock Organ
		instrumentMap.put(19, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Church Organ
		instrumentMap.put(20, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Reed Organ
		instrumentMap.put(21, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Accordian
		instrumentMap.put(22, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Harmonica
		instrumentMap.put(23, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Tango Accordian

		// Guitar (BIT DIDGERIDOO BELL)
		instrumentMap.put(24, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Acoustic Guitar (nylon)
		instrumentMap.put(25, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Acoustic Guitar (steel)
		instrumentMap.put(26, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Electric Guitar (jazz)
		instrumentMap.put(27, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Electric Guitar (clean)
		instrumentMap.put(28, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Electric Guitar (muted)
		instrumentMap.put(29, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Overdriven Guitar
		instrumentMap.put(30, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Distortion Guitar
		instrumentMap.put(31, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Guitar Harmonics

		// Bass
		instrumentMap.put(32, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Acoustic Bass
		instrumentMap.put(33, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Electric Bass (finger)
		instrumentMap.put(34, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Electric Bass (pick)
		instrumentMap.put(35, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Fretless Bass
		instrumentMap.put(36, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Slap Bass 1
		instrumentMap.put(37, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Slap Bass 2
		instrumentMap.put(38, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Synth Bass 1
		instrumentMap.put(39, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE}); // Synth Bass 2

		// Strings
		instrumentMap.put(40, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Violin
		instrumentMap.put(41, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Viola
		instrumentMap.put(42, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Cello
		instrumentMap.put(43, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.GUITAR, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Contrabass
		instrumentMap.put(44, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Tremolo Strings
		instrumentMap.put(45, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Pizzicato Strings
		instrumentMap.put(46, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.CHIME}); // Orchestral Harp
		instrumentMap.put(47, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Timpani

		// Ensenble
		instrumentMap.put(48, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // String Ensemble 1
		instrumentMap.put(49, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // String Ensemble 2
		instrumentMap.put(50, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Synth Strings 1
		instrumentMap.put(51, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Synth Strings 2
		instrumentMap.put(52, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Choir Aahs
		instrumentMap.put(53, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Voice Oohs
		instrumentMap.put(54, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Synth Choir
		instrumentMap.put(55, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL}); // Orchestra Hit

		// Brass
		instrumentMap.put(56, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(57, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(58, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(59, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(60, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(61, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(62, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(63, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});

		// Reed
		instrumentMap.put(64, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(65, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(66, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(67, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(68, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(69, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(70, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(71, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});

		// Pipe
		instrumentMap.put(72, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(73, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(74, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(75, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(76, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(77, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(78, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(79, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.FLUTE, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});

		// Synth Lead
		instrumentMap.put(80, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(81, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(82, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(83, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(84, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(85, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(86, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(87, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});

		// Synth Pad
		instrumentMap.put(88, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(89, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(90, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(91, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(92, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(93, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(94, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(95, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});

		// Synth Effects
//		instrumentMap.put(96, new Instrument[]{});
//		instrumentMap.put(97, new Instrument[]{});
		instrumentMap.put(98, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BIT, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(99, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(100, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(101, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(102, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(103, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});

		// Ethnic
		instrumentMap.put(104, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BANJO, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(105, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BANJO, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(106, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BANJO, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(107, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BANJO, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(108, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.BANJO, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(109, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(110, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});
		instrumentMap.put(111, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.HARP, com.github.hhhzzzsss.songplayer.song.Instrument.DIDGERIDOO, com.github.hhhzzzsss.songplayer.song.Instrument.BELL});

		// Percussive
		instrumentMap.put(112, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE});
		instrumentMap.put(113, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE});
		instrumentMap.put(114, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE});
		instrumentMap.put(115, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE});
		instrumentMap.put(116, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE});
		instrumentMap.put(117, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE});
		instrumentMap.put(118, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE});
		instrumentMap.put(119, new com.github.hhhzzzsss.songplayer.song.Instrument[]{com.github.hhhzzzsss.songplayer.song.Instrument.IRON_XYLOPHONE, com.github.hhhzzzsss.songplayer.song.Instrument.BASS, com.github.hhhzzzsss.songplayer.song.Instrument.XYLOPHONE});
	}

	public static HashMap<Integer, Integer> percussionMap = new HashMap<>();
	static {
		percussionMap.put(35, 10 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(36, 6  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(37, 6  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(38, 8  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(39, 6  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(40, 4  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(41, 6  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(42, 22 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(43, 13 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(44, 22 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(45, 15 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(46, 18 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(47, 20 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(48, 23 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(49, 17 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(50, 23 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(51, 24 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(52, 8  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(53, 13 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(54, 18 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(55, 18 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(56, 1  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(57, 13 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(58, 2  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(59, 13 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(60, 9  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(61, 2  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(62, 8  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(63, 22 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(64, 15 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(65, 13 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(66, 8  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(67, 8  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(68, 3  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(69, 20 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(70, 23 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(71, 24 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(72, 24 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(73, 17 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(74, 11 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(75, 18 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(76, 9  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(77, 5  + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(78, 22 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(79, 19 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(80, 17 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(81, 22 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(82, 22 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.SNARE.instrumentId);
		percussionMap.put(83, 24 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.CHIME.instrumentId);
		percussionMap.put(84, 24 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.CHIME.instrumentId);
		percussionMap.put(85, 21 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.HAT.instrumentId);
		percussionMap.put(86, 14 + 25* com.github.hhhzzzsss.songplayer.song.Instrument.BASEDRUM.instrumentId);
		percussionMap.put(87, 7  + 25* Instrument.BASEDRUM.instrumentId);
	}
}
