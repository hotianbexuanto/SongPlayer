package com.github.hhhzzzsss.songplayer;

import com.github.hhhzzzsss.songplayer.item.SongItemCreatorThread;
import com.github.hhhzzzsss.songplayer.item.SongItemUtils;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.playing.Stage;
import com.github.hhhzzzsss.songplayer.song.Note;
import com.github.hhhzzzsss.songplayer.song.Playlist;
import com.github.hhhzzzsss.songplayer.song.Song;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.hhhzzzsss.songplayer.SongPlayer.MC;

public class CommandProcessor {
	public static ArrayList<Command> commands = new ArrayList<>();
	public static HashMap<String, Command> commandMap = new HashMap<>();
	public static ArrayList<String> commandCompletions = new ArrayList<>();

	public static void initCommands() {
		commands.add(new helpCommand());
		commands.add(new setPrefixCommand());
		commands.add(new playCommand());
		commands.add(new stopCommand());
		commands.add(new skipCommand());
		commands.add(new gotoCommand());
		commands.add(new loopCommand());
		commands.add(new statusCommand());
		commands.add(new queueCommand());
		commands.add(new songsCommand());
		commands.add(new playlistCommand());
		commands.add(new setCreativeCommandCommand());
		commands.add(new setSurvivalCommandCommand());
		commands.add(new useEssentialsCommandsCommand());
		commands.add(new useVanillaCommandsCommand());
		commands.add(new toggleFakePlayerCommand());
		commands.add(new setStageTypeCommand());
		commands.add(new breakSpeedCommand());
		commands.add(new placeSpeedCommand());
		commands.add(new toggleMovementCommand());
		commands.add(new setVelocityThresholdCommand());
		commands.add(new toggleAutoCleanupCommand());
		commands.add(new cleanupLastStageCommand());
		commands.add(new announcementCommand());
		commands.add(new toggleSurvivalOnlyCommand());
		commands.add(new toggleFlightNoclipCommand());
		commands.add(new songItemCommand());
		commands.add(new testSongCommand());

		for (Command command : commands) {
			commandMap.put(command.getName().toLowerCase(Locale.ROOT), command);
			commandCompletions.add(command.getName());
			for (String alias : command.getAliases()) {
				commandMap.put(alias.toLowerCase(Locale.ROOT), command);
				commandCompletions.add(alias);
			}
		}
	}

	// returns true if it is a command and should be cancelled
	public static boolean processChatMessage(String message) {
		if (message.startsWith(Config.getConfig().prefix)) {
			String[] parts = message.substring(Config.getConfig().prefix.length()).split(" ", 2);
			String name = parts.length>0 ? parts[0] : "";
			String args = parts.length>1 ? parts[1] : "";
			Command c = commandMap.get(name.toLowerCase(Locale.ROOT));
			if (c == null) {
				Util.showChatMessage("§c无法识别的命令");
			} else {
				try {
					boolean success = c.processCommand(args);
					if (!success) {
						if (c.getSyntax().length == 0) {
							Util.showChatMessage("§cSyntax: " + Config.getConfig().prefix + c.getName());
						}
						else if (c.getSyntax().length == 1) {
							Util.showChatMessage("§cSyntax: " + Config.getConfig().prefix + c.getName() + " " + c.getSyntax()[0]);
						}
						else {
							Util.showChatMessage("§cSyntax:");
							for (String syntax : c.getSyntax()) {
								Util.showChatMessage("§c    " + Config.getConfig().prefix + c.getName() + " " + syntax);
							}
						}
					}
				}
				catch (Throwable e) {
					e.printStackTrace();
					Util.showChatMessage("§c执行此命令时发生错误: §4" + e.getMessage());
				}
			}
			return true;
		} else {
			return false;
		}
	}

	private static abstract class Command {
		public abstract String getName();
		public abstract String[] getSyntax();
		public abstract String getDescription();
		public abstract boolean processCommand(String args);
		public String[] getAliases() {
			return new String[0];
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			return null;
		}
	}

	private static class helpCommand extends Command {
		public String getName() {
			return "help";
		}
		public String[] getSyntax() {
			return new String[]{"[command]"};
		}
		public String getDescription() {
			return "列出命令或解释命令";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				StringBuilder helpMessage = new StringBuilder("§6命令 -");
				for (Command c : commands) {
					helpMessage.append(" " + Config.getConfig().prefix + c.getName());
				}
				Util.showChatMessage(helpMessage.toString());
			}
			else {
				if (commandMap.containsKey(args.toLowerCase(Locale.ROOT))) {
					Command c = commandMap.get(args.toLowerCase(Locale.ROOT));
					Util.showChatMessage("§6------------------------------");
					Util.showChatMessage("§6帮助: §3" + c.getName());
					Util.showChatMessage("§6描述: §3" + c.getDescription());
					if (c.getSyntax().length == 0) {
						Util.showChatMessage("§6用法: §3" + Config.getConfig().prefix + c.getName());
					}
					else if (c.getSyntax().length == 1) {
						Util.showChatMessage("§6用法: §3" + Config.getConfig().prefix + c.getName() + " " + c.getSyntax()[0]);
					} else {
						Util.showChatMessage("§6用法:");
						for (String syntax : c.getSyntax()) {
							Util.showChatMessage("    §3" + Config.getConfig().prefix + c.getName() + " " + syntax);
						}
					}
					if (c.getAliases().length > 0) {
						Util.showChatMessage("§6别名: §3" + String.join(", ", c.getAliases()));
					}
					Util.showChatMessage("§6------------------------------");
				} else {
					Util.showChatMessage("§c无法识别的命令: " + args);
				}
			}
			return true;
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			return CommandSource.suggestMatching(commandCompletions, suggestionsBuilder);
		}
	}

	private static class setPrefixCommand extends Command {
		public String getName() {
			return "setPrefix";
		}
		public String[] getAliases() {
			return new String[]{"prefix"};
		}
		public String[] getSyntax() {
			return new String[]{"<prefix>"};
		}
		public String getDescription() {
			return "设置SongPlayer使用的命令前缀";
		}
		public boolean processCommand(String args) {
			if (args.contains(" ")) {
				Util.showChatMessage("§c前缀不能包含空格");
				return true;
			}
			else if (args.startsWith("/")) {
				Util.showChatMessage("§c前缀不能以/开头");
				return true;
			}
			else if (args.length() > 0) {
				Config.getConfig().prefix = args;
				Util.showChatMessage("§6已将前缀设置为 " + args);
				Config.saveConfigWithErrorHandling();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class playCommand extends Command {
		public String getName() {
			return "play";
		}
		public String[] getSyntax() {
			return new String[]{"<song or url>"};
		}
		public String getDescription() {
			return "播放一首歌曲";
		}
		public boolean processCommand(String args) {
			if (args.length() > 0) {
				if (Config.getConfig().survivalOnly && SongPlayer.MC.interactionManager.getCurrentGameMode() != GameMode.SURVIVAL) {
					Util.showChatMessage("§c要在仅生存模式下播放，您必须首先处于生存模式。");
					return true;
				}

				SongHandler.getInstance().loadSong(args);
				return true;
			}
			else {
				return false;
			}
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			return Util.giveSongSuggestions(args, suggestionsBuilder);
		}
	}

	private static class stopCommand extends Command {
		public String getName() {
			return "stop";
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "停止播放";
		}
		public boolean processCommand(String args) {
			if (SongHandler.getInstance().isIdle()) {
				Util.showChatMessage("§6当前没有歌曲在播放");
				return true;
			}
			if (args.length() == 0) {
				if (SongHandler.getInstance().cleaningUp) {
					SongHandler.getInstance().restoreStateAndReset();
					Util.showChatMessage("§6已停止清理");
				} else if (Config.getConfig().autoCleanup && SongHandler.getInstance().originalBlocks.size() != 0) {
					SongHandler.getInstance().partialResetAndCleanup();
					Util.showChatMessage("§6已停止播放并切换到清理模式");
				} else {
					SongHandler.getInstance().restoreStateAndReset();
					Util.showChatMessage("§6已停止播放");
				}
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class skipCommand extends Command {
		public String getName() {
			return "skip";
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "跳过当前歌曲";
		}
		public boolean processCommand(String args) {
			if (SongHandler.getInstance().currentSong == null) {
				Util.showChatMessage("§6当前没有歌曲在播放");
				return true;
			}
			if (args.length() == 0) {
				SongHandler.getInstance().currentSong = null;
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class gotoCommand extends Command {
		public String getName() {
			return "goto";
		}
		public String[] getSyntax() {
			return new String[]{"<mm:ss>"};
		}
		public String getDescription() {
			return "跳转到歌曲的特定时间点";
		}
		public boolean processCommand(String args) {
			if (SongHandler.getInstance().currentSong == null) {
				Util.showChatMessage("§6当前没有歌曲在播放");
				return true;
			}

			if (args.length() > 0) {
				try {
					long time = Util.parseTime(args);
					SongHandler.getInstance().currentSong.setTime(time);
					Util.showChatMessage("§6已将歌曲时间设置为 §3" + Util.formatTime(time));
					return true;
				} catch (IOException e) {
					Util.showChatMessage("§c时间戳无效");
					return false;
				}
			}
			else {
				return false;
			}
		}
	}

	private static class loopCommand extends Command {
		public String getName() {
			return "loop";
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "切换歌曲循环播放";
		}
		public boolean processCommand(String args) {
			if (SongHandler.getInstance().currentSong == null) {
				Util.showChatMessage("§6当前没有歌曲在播放");
				return true;
			}

			SongHandler.getInstance().currentSong.looping = !SongHandler.getInstance().currentSong.looping;
			SongHandler.getInstance().currentSong.loopCount = 0;
			if (SongHandler.getInstance().currentSong.looping) {
				Util.showChatMessage("§6已启用循环播放");
			}
			else {
				Util.showChatMessage("§6已禁用循环播放");
			}
			return true;
		}
	}

	private static class statusCommand extends Command {
		public String getName() {
			return "status";
		}
		public String[] getAliases() {
			return new String[]{"current"};
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "获取当前播放歌曲的状态";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				if (SongHandler.getInstance().currentSong == null) {
					Util.showChatMessage("§6当前没有歌曲在播放");
					return true;
				}
				Song currentSong = SongHandler.getInstance().currentSong;
				long currentTime = Math.min(currentSong.time, currentSong.length);
				long totalTime = currentSong.length;
				Util.showChatMessage(String.format("§6正在播放 %s §3(%s/%s)", currentSong.name, Util.formatTime(currentTime), Util.formatTime(totalTime)));
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class queueCommand extends Command {
		public String getName() {
			return "queue";
		}
		public String[] getAliases() {
			return new String[]{"showQueue"};
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "显示当前歌曲队列";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				if (SongHandler.getInstance().currentSong == null && SongHandler.getInstance().songQueue.isEmpty()) {
					Util.showChatMessage("§6当前没有歌曲在播放");
					return true;
				}

				Util.showChatMessage("§6------------------------------");
				if (SongHandler.getInstance().currentSong != null) {
					Util.showChatMessage("§6当前歌曲: §3" + SongHandler.getInstance().currentSong.name);
				}
				int index = 0;
				for (Song song : SongHandler.getInstance().songQueue) {
					index++;
					Util.showChatMessage(String.format("§6%d. §3%s", index, song.name));
				}
				Util.showChatMessage("§6------------------------------");
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class songsCommand extends Command {
		public String getName() {
			return "songs";
		}
		public String[] getAliases() {
			return new String[]{"list"};
		}
		public String[] getSyntax() {
			return new String[]{
					"",
					"<subdirectory>"};
		}
		public String getDescription() {
			return "列出可用的歌曲。如果提供了参数，则列出子目录中的所有歌曲。";
		}
		public boolean processCommand(String args) {
			if (!args.contains(" ")) {
				Path dir;
				if (args.length() == 0) {
					dir = SongPlayer.SONG_DIR;
				}
				else {
					dir = SongPlayer.SONG_DIR.resolve(args);
					if (!Files.isDirectory(dir)) {
						Util.showChatMessage("§c未找到目录");
						return true;
					}
				}

				List<String> subdirectories = null;
				List<String> songs = null;
				try {
					subdirectories = Files.list(dir)
							.filter(Files::isDirectory)
							.map(Path::getFileName)
							.map(Path::toString)
							.map(str -> str + "/")
							.collect(Collectors.toList());
					songs = Files.list(dir)
							.filter(Files::isRegularFile)
							.map(Path::getFileName)
							.map(Path::toString)
							.collect(Collectors.toList());
				}
				catch (IOException e) {
					Util.showChatMessage("§c读取文件夹时出错: §4" + e.getMessage());
					return true;
				}

				if (subdirectories.size() == 0 && songs.size() == 0) {
					Util.showChatMessage("§b未找到歌曲。您可以将midi或nbs文件放在 §3.minecraft/songs §6文件夹中。");
				}
				else {
					Util.showChatMessage("§6----------------------------------------");
					Util.showChatMessage("§e.minecraft/songs/" + args + " 的内容");
					if (subdirectories.size() > 0) {
						Util.showChatMessage("§6子目录: §3" + String.join(" ", subdirectories));
					}
					if (songs.size() > 0) {
						Util.showChatMessage("§6歌曲: §7" + String.join(", ", songs));
					}
					Util.showChatMessage("§6----------------------------------------");
				}
				return true;
			}
			else {
				return false;
			}
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			return Util.giveSongDirectorySuggestions(args, suggestionsBuilder);
		}
	}

	private static class playlistCommand extends Command {
		public String getName() {
			return "playlist";
		}
		public String[] getSyntax() {
			return new String[]{
					"play <playlist>",
					"create <playlist>",
					"list [<playlist>]",
					"delete <playlist> <song>",
					"addSong <playlist> <song>",
					"removeSong <playlist> <song>",
					"renameSong <playlist> <index> <new name>",
					"loop",
					"shuffle",
			};
		}
		public String getDescription() {
			return "配置播放列表";
		}
		public boolean processCommand(String args) {
			String[] split = args.split(" ");

			if (split.length < 1) return false;

			try {
				Path playlistDir = null;
				if (split.length >= 2) {
					playlistDir = SongPlayer.PLAYLISTS_DIR.resolve(split[1]);
				}
				switch (split[0].toLowerCase(Locale.ROOT)) {
					case "play": {
						if (split.length != 2) return false;
						if (!Files.exists(playlistDir)) {
							Util.showChatMessage("§c播放列表不存在");
							return true;
						}
						SongHandler.getInstance().setPlaylist(playlistDir);
						return true;
					}
					case "create": {
						if (split.length > 2) {
							Util.showChatMessage("§c播放列表名称中不能包含空格");
							return true;
						}
						if (split.length != 2) return false;
						Playlist.createPlaylist(split[1]);
						Util.showChatMessage(String.format("§6已创建播放列表 §3%s", split[1]));
						return true;
					}
					case "delete": {
						if (split.length != 2) return false;
						Playlist.deletePlaylist(playlistDir);
						Util.showChatMessage(String.format("§6已删除播放列表 §3%s", split[1]));
						return true;
					}
					case "list": {
						if (split.length == 1) {
							if (!Files.exists(SongPlayer.PLAYLISTS_DIR)) return true;
							List<String> playlists = Files.list(SongPlayer.PLAYLISTS_DIR)
									.filter(Files::isDirectory)
									.map(Path::getFileName)
									.map(Path::toString)
									.collect(Collectors.toList());
							if (playlists.size() == 0) {
								Util.showChatMessage("§6未找到播放列表");
							} else {
								Util.showChatMessage("§6播放列表: §3" + String.join(", ", playlists));
							}
							return true;
						}
						List<String> playlistIndex = Playlist.listSongs(playlistDir);
						Util.showChatMessage("§6------------------------------");
						int index = 0;
						for (String songName : playlistIndex) {
							index++;
							Util.showChatMessage(String.format("§6%d. §3%s", index, songName));
						}
						Util.showChatMessage("§6------------------------------");
						return true;
					}
					case "addsong": {
						if (split.length < 3) return false;
						String location = String.join(" ", Arrays.copyOfRange(split, 2, split.length));
						Playlist.addSong(playlistDir, SongPlayer.SONG_DIR.resolve(location));
						Util.showChatMessage(String.format("§6已将 §3%s §6添加到 §3%s", location, split[1]));
						return true;
					}
					case "removesong": {
						if (split.length < 3) return false;
						String location = String.join(" ", Arrays.copyOfRange(split, 2, split.length));
						Playlist.removeSong(playlistDir, location);
						Util.showChatMessage(String.format("§6已从 §3%s §6中移除 §3%s", location, split[1]));
						return true;
					}
					case "renamesong": {
						if (split.length < 4) return false;
						String location = String.join(" ", Arrays.copyOfRange(split, 3, split.length));
						int index = 0;
						try {
							index = Integer.parseInt(split[2]);
						}
						catch (Exception e) {
							Util.showChatMessage(String.format("§c索引必须是整数"));
							return true;
						}
						String oldName = Playlist.renameSong(playlistDir, index-1, location);
						Util.showChatMessage(String.format("§6已将 §3%s §6重命名为 §3%s", oldName, location));
						return true;
					}
					case "loop": {
						if (split.length != 1) return false;
						Config.getConfig().loopPlaylists = !Config.getConfig().loopPlaylists;
						SongHandler.getInstance().setPlaylistLoop(Config.getConfig().loopPlaylists);
						if (Config.getConfig().loopPlaylists) {
							Util.showChatMessage("§6已启用播放列表循环");
						} else {
							Util.showChatMessage("§6已禁用播放列表循环");
						}
						Config.saveConfigWithErrorHandling();
						return true;
					}
					case "shuffle": {
						if (split.length != 1) return false;
						Config.getConfig().shufflePlaylists = !Config.getConfig().shufflePlaylists;
						SongHandler.getInstance().setPlaylistShuffle(Config.getConfig().shufflePlaylists);
						if (Config.getConfig().shufflePlaylists) {
							Util.showChatMessage("§6已启用播放列表随机播放");
						} else {
							Util.showChatMessage("§6已禁用播放列表随机播放");
						}
						Config.saveConfigWithErrorHandling();
						return true;
					}
					default: {
						return false;
					}
				}
			}
			catch (IOException e) {
				Util.showChatMessage("§c" + e.getMessage());
				return true;
			}
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			String[] split = args.split(" ", -1);
			if (split.length <= 1) {
				return CommandSource.suggestMatching(new String[]{
						"play",
						"create",
						"delete",
						"list",
						"addSong",
						"removeSong",
						"renameSong",
						"loop",
						"shuffle",
				}, suggestionsBuilder);
			}
			switch (split[0].toLowerCase(Locale.ROOT)) {
				case "create":
				case "loop":
				case "shuffle":
				default: {
					return null;
				}
				case "play":
				case "list":
				case "delete": {
					if (split.length == 2) {
						return Util.givePlaylistSuggestions(suggestionsBuilder);
					}
					return null;
				}
				case "addsong": {
					if (split.length == 2) {
						return Util.givePlaylistSuggestions(suggestionsBuilder);
					} else if (split.length >= 3) {
						String location = String.join(" ", Arrays.copyOfRange(split, 2, split.length));
						return Util.giveSongSuggestions(location, suggestionsBuilder);
					}
					return null;
				}
				case "removesong": {
					if (split.length == 2) {
						return Util.givePlaylistSuggestions(suggestionsBuilder);
					} else if (split.length == 3) {
						Path playlistDir = SongPlayer.PLAYLISTS_DIR.resolve(split[1]);
						Stream<Path> playlistFiles = Playlist.getSongFiles(playlistDir);
						if (playlistFiles == null) {
							return null;
						}
						return CommandSource.suggestMatching(
								playlistFiles.map(Path::getFileName)
										.map(Path::toString),
								suggestionsBuilder);
					}
					return null;
				}
				case "renamesong": {
					if (split.length == 2) {
						return Util.givePlaylistSuggestions(suggestionsBuilder);
					} else if (split.length == 3) {
						Path playlistDir = SongPlayer.PLAYLISTS_DIR.resolve(split[1]);
						Stream<Path> playlistFiles = Playlist.getSongFiles(playlistDir);
						if (playlistFiles == null) {
							return null;
						}
						int max = playlistFiles.collect(Collectors.toList()).size();
						Stream<String> suggestions = IntStream.range(1, max+1).mapToObj(Integer::toString);
						return CommandSource.suggestMatching(suggestions, suggestionsBuilder);
					}
					return null;
				}
			}
		}
	}

	private static class setCreativeCommandCommand extends Command {
		public String getName() {
			return "setCreativeCommand";
		}
		public String[] getAliases() {
			return new String[]{"sc"};
		}
		public String[] getSyntax() {
			return new String[]{"<command>"};
		}
		public String getDescription() {
			return "设置用于进入创造模式的命令";
		}
		public boolean processCommand(String args) {
			if (args.length() > 0) {
				if (args.startsWith("/")) {
					Config.getConfig().creativeCommand = args.substring(1);
				} else {
					Config.getConfig().creativeCommand = args;
				}
				Util.showChatMessage("§6已将创造模式命令设置为 §3/" + Config.getConfig().creativeCommand);
				Config.saveConfigWithErrorHandling();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class setSurvivalCommandCommand extends Command {
		public String getName() {
			return "setSurvivalCommand";
		}
		public String[] getAliases() {
			return new String[]{"ss"};
		}
		public String[] getSyntax() {
			return new String[]{"<command>"};
		}
		public String getDescription() {
			return "设置用于进入生存模式的命令";
		}
		public boolean processCommand(String args) {
			if (args.length() > 0) {
				if (args.startsWith("/")) {
					Config.getConfig().survivalCommand = args.substring(1);
				} else {
					Config.getConfig().survivalCommand = args;
				}
				Util.showChatMessage("§6已将生存模式命令设置为 §3/" + Config.getConfig().survivalCommand);
				Config.saveConfigWithErrorHandling();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class useEssentialsCommandsCommand extends Command {
		public String getName() {
			return "useEssentialsCommands";
		}
		public String[] getAliases() {
			return new String[]{"essentials", "useEssentials", "essentialsCommands"};
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "切换到使用Essentials游戏模式命令";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				Config.getConfig().creativeCommand = "gmc";
				Config.getConfig().survivalCommand = "gms";
				Util.showChatMessage("§6现在使用Essentials游戏模式命令");
				Config.saveConfigWithErrorHandling();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class useVanillaCommandsCommand extends Command {
		public String getName() {
			return "useVanillaCommands";
		}
		public String[] getAliases() {
			return new String[]{"vanilla", "useVanilla", "vanillaCommands"};
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "切换到使用原版游戏模式命令";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				Config.getConfig().creativeCommand = "gamemode creative";
				Config.getConfig().survivalCommand = "gamemode survival";
				Util.showChatMessage("§6现在使用原版游戏模式命令");
				Config.saveConfigWithErrorHandling();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class toggleFakePlayerCommand extends Command {
		public String getName() {
			return "toggleFakePlayer";
		}
		public String[] getAliases() {
			return new String[]{"fakePlayer", "fp"};
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "在播放歌曲时显示代表您真实位置的假玩家";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				Config.getConfig().showFakePlayer = !Config.getConfig().showFakePlayer;
				if (Config.getConfig().showFakePlayer) {
					Util.showChatMessage("§6已启用假玩家");
				}
				else {
					Util.showChatMessage("§6已禁用假玩家");
				}
				Config.saveConfigWithErrorHandling();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class setStageTypeCommand extends Command {
		public String getName() {
			return "setStageType";
		}
		public String[] getAliases() {
			return new String[]{"setStage", "stageType"};
		}
		public String[] getSyntax() {
			return new String[]{"<DEFAULT | WIDE | SPHERICAL>"};
		}
		public String getDescription() {
			return "设置要构建的音符盒舞台类型";
		}
		public boolean processCommand(String args) {
			if (args.length() > 0) {
				try {
					Stage.StageType stageType = Stage.StageType.valueOf(args.toUpperCase(Locale.ROOT));
					Config.getConfig().stageType = stageType;
					Util.showChatMessage("§6已将舞台类型设置为 §3" + stageType.name());
					Config.saveConfigWithErrorHandling();
				}
				catch (IllegalArgumentException e) {
					Util.showChatMessage("§c无效的舞台类型");
				}
				return true;
			}
			else {
				return false;
			}
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			if (!args.contains(" ")) {
				return CommandSource.suggestMatching(Arrays.stream(Stage.StageType.values()).map(Stage.StageType::name), suggestionsBuilder);
			}
			else {
				return null;
			}
		}
	}

	private static class breakSpeedCommand extends Command {
		public String getName() {
			return "breakSpeed";
		}
		public String[] getSyntax() {
			return new String[]{
					"set <speed>",
					"reset"
			};
		}
		public String getDescription() {
			return "设置方块破坏速度（方块/秒）";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				return false;
			}

			String[] split = args.split(" ");
			switch (split[0].toLowerCase(Locale.ROOT)) {
				case "set":
					if (split.length != 2) return false;
					double speed;
					try {
						speed = Double.parseDouble(split[1]);
					} catch (NumberFormatException e) {
						Util.showChatMessage("§c速度必须是数字");
						return true;
					}
					if (speed <= 0) {
						Util.showChatMessage("§c速度必须大于0");
						return true;
					}
					Config.getConfig().breakSpeed = speed;
					Config.saveConfigWithErrorHandling();
					Util.showChatMessage("§6已将方块破坏速度设置为 §3" + Config.getConfig().breakSpeed + " §6方块/秒");
					return true;
				case "reset":
					if (split.length != 1) return false;
					Config.getConfig().breakSpeed = 40;
					Config.saveConfigWithErrorHandling();
					Util.showChatMessage("§6已重置方块破坏速度为 §3" + Config.getConfig().breakSpeed + " §6方块/秒");
					return true;
				default:
					return false;
			}
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			String[] split = args.split(" ", -1);
			if (split.length <= 1) {
				return CommandSource.suggestMatching(new String[]{
						"set",
						"reset",
				}, suggestionsBuilder);
			} else {
				return null;
			}
		}
	}

	private static class placeSpeedCommand extends Command {
		public String getName() {
			return "placeSpeed";
		}
		public String[] getSyntax() {
			return new String[]{
					"set <speed>",
					"reset"
			};
		}
		public String getDescription() {
			return "设置方块放置速度（方块/秒）";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				return false;
			}

			String[] split = args.split(" ");
			switch (split[0].toLowerCase(Locale.ROOT)) {
				case "set":
					if (split.length != 2) return false;
					double speed;
					try {
						speed = Double.parseDouble(split[1]);
					} catch (NumberFormatException e) {
						Util.showChatMessage("§c速度必须是数字");
						return true;
					}
					if (speed <= 0) {
						Util.showChatMessage("§c速度必须大于0");
						return true;
					}
					Config.getConfig().placeSpeed = speed;
					Config.saveConfigWithErrorHandling();
					Util.showChatMessage("§6已将方块放置速度设置为 §3" + Config.getConfig().placeSpeed + " §6方块/秒");
					return true;
				case "reset":
					if (split.length != 1) return false;
					Config.getConfig().placeSpeed = 20;
					Config.saveConfigWithErrorHandling();
					Util.showChatMessage("§6已重置方块放置速度为 §3" + Config.getConfig().placeSpeed + " §6方块/秒");
					return true;
				default:
					return false;
			}
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			String[] split = args.split(" ", -1);
			if (split.length <= 1) {
				return CommandSource.suggestMatching(new String[]{
						"set",
						"reset",
				}, suggestionsBuilder);
			} else {
				return null;
			}
		}
	}

	private static class toggleMovementCommand extends Command {
		public String getName() {
			return "toggleMovement";
		}
		public String[] getAliases() {
			return new String[]{"movement"};
		}
		public String[] getSyntax() {
			return new String[]{"<swing | rotate>"};
		}
		public String getDescription() {
			return "切换不同类型的移动";
		}
		public boolean processCommand(String args) {
			switch (args.toLowerCase(Locale.ROOT)) {
				case "swing":
					Config.getConfig().swing = !Config.getConfig().swing;
					if (Config.getConfig().swing) {
						Util.showChatMessage("§6已启用手臂摆动");
					}
					else {
						Util.showChatMessage("§6已禁用手臂摆动");
					}
					Config.saveConfigWithErrorHandling();
					return true;
				case "rotate":
					Config.getConfig().rotate = !Config.getConfig().rotate;
					if (Config.getConfig().rotate) {
						Util.showChatMessage("§6已启用玩家旋转");
					}
					else {
						Util.showChatMessage("§6已禁用玩家旋转");
					}
					Config.saveConfigWithErrorHandling();
					return true;
				default:
					return false;
			}
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			if (!args.contains(" ")) {
				return CommandSource.suggestMatching(new String[]{"swing", "rotate"}, suggestionsBuilder);
			}
			else {
				return null;
			}
		}
	}

	private static class setVelocityThresholdCommand extends Command {
		public String getName() {
			return "setVelocityThreshold";
		}
		public String[] getAliases() {
			return new String[]{"velocityThreshold", "threshold"};
		}
		public String[] getSyntax() {
			return new String[]{"<threshold>"};
		}
		public String getDescription() {
			return "设置音符不被播放的最小速度阈值（适用于midi和nbs）。这个值必须是0到100之间的数字。对于歌曲物品，阈值在物品创建时就已经确定。";
		}
		public boolean processCommand(String args) {
			if (args.length() > 0) {
				try {
					int threshold = Integer.parseInt(args);
					if (threshold < 0 || threshold > 100) {
						Util.showChatMessage("§c速度阈值必须是0到100之间的值");
						return true;
					}
					Config.getConfig().velocityThreshold = threshold;
					Util.showChatMessage("§6已将速度阈值设置为 " + threshold);
					Config.saveConfigWithErrorHandling();
					return true;
				} catch (NumberFormatException e) {
					return false;
				}
			} else {
				return false;
			}
        }
	}

	private static class toggleAutoCleanupCommand extends Command {
		public String getName() {
			return "toggleAutoCleanup";
		}
		public String[] getAliases() {
			return new String[]{"autoCleanup"};
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "切换在播放后是否自动清理舞台并恢复原始方块";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				Config.getConfig().autoCleanup = !Config.getConfig().autoCleanup;
				if (Config.getConfig().autoCleanup) {
					Util.showChatMessage("§6已启用自动清理");
				}
				else {
					Util.showChatMessage("§6已禁用自动清理");
				}
				Config.saveConfigWithErrorHandling();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class cleanupLastStageCommand extends Command {
		public String getName() {
			return "cleanupLastStage";
		}
		public String[] getAliases() {
			return new String[]{};
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "清理您最近的舞台并恢复原始方块";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				Stage lastStage = SongHandler.getInstance().lastStage;
				if (!SongHandler.getInstance().isIdle()) {
					Util.showChatMessage("§c如果您正在执行其他操作，无法开始清理");
					return true;
				}
				if (lastStage == null || SongHandler.getInstance().originalBlocks.size() == 0 || !lastStage.serverIdentifier.equals(Util.getServerIdentifier())) {
					Util.showChatMessage("§6没有需要清理的内容");
					return true;
				}
				if (MC.player.getPos().squaredDistanceTo(lastStage.getOriginBottomCenter()) > 3*3 || !lastStage.worldName.equals(Util.getWorldName())) {
					String coordStr = String.format(
							"%d %d %d",
							lastStage.position.getX(), lastStage.position.getY(), lastStage.position.getZ()
					);
					Util.showChatMessage("§6您必须在舞台中心 §33 §6个方块范围内才能开始清理。");
					MutableText coordText = Util.joinTexts(null,
							Text.literal("位置在 ").setStyle(Style.EMPTY.withColor(Formatting.GOLD)),
							Text.literal(coordStr).setStyle(
									Style.EMPTY
											.withColor(Formatting.DARK_AQUA)
											.withUnderline(true)
											.withClickEvent(new ClickEvent.CopyToClipboard(coordStr))
											.withHoverEvent(new HoverEvent.ShowText(Text.literal("复制 \"" + coordStr + "\"")))
							),
							Text.literal(" 世界中 ").setStyle(Style.EMPTY.withColor(Formatting.GOLD)),
							Text.literal(lastStage.worldName).setStyle(Style.EMPTY.withColor(Formatting.DARK_AQUA))
					);
					Util.showChatMessage(coordText);
					return true;
				}

				SongHandler.getInstance().startCleanup();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class announcementCommand extends Command {
		public String getName() {
			return "announcement";
		}
		public String[] getSyntax() {
			return new String[]{
					"enable",
					"disable",
					"getMessage",
					"setMessage <message>",
			};
		}
		public String getDescription() {
			return "设置在您开始播放歌曲时发送的公告消息。使用setMessage时，在歌曲名称应出现的位置写[name]。";
		}
		public boolean processCommand(String args) {
			String[] split = args.split(" ", 2);
			switch (split[0].toLowerCase(Locale.ROOT)) {
				case "enable":
					if (split.length != 1) return false;
					Config.getConfig().doAnnouncement = true;
					Util.showChatMessage("§6已启用歌曲公告");
					Config.saveConfigWithErrorHandling();
					return true;
				case "disable":
					if (split.length != 1) return false;
					Config.getConfig().doAnnouncement = false;
					Util.showChatMessage("§6已禁用歌曲公告");
					Config.saveConfigWithErrorHandling();
					return true;
				case "getmessage":
					if (split.length != 1) return false;
					Util.showChatMessage("§6当前公告消息是 §r" + Config.getConfig().announcementMessage);
					return true;
				case "setmessage":
					if (split.length != 2) return false;
					Config.getConfig().announcementMessage = split[1];
					Util.showChatMessage("§6已将公告消息设置为 §r" + split[1]);
					Config.saveConfigWithErrorHandling();
					return true;
				default:
					return false;
			}
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			if (!args.contains(" ")) {
				return CommandSource.suggestMatching(new String[]{"enable", "disable", "getMessage", "setMessage"}, suggestionsBuilder);
			}
			else {
				return null;
			}
		}
	}

	private static class toggleSurvivalOnlyCommand extends Command {
		public String getName() {
			return "toggleSurvivalOnly";
		}
		public String[] getAliases() {
			return new String[]{"survivalOnly"};
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "启用或禁用仅生存模式，在该模式下自动音符盒放置被禁用，自动调音通过右键点击完成。";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				if (!SongHandler.getInstance().isIdle()) {
					Util.showChatMessage("§c您不能在播放或构建时更改此设置");
					return true;
				}

				Config.getConfig().survivalOnly = !Config.getConfig().survivalOnly;
				if (Config.getConfig().survivalOnly) {
					Util.showChatMessage("§6已启用仅生存模式");
				}
				else {
					Util.showChatMessage("§6已禁用仅生存模式");
				}
				Config.saveConfigWithErrorHandling();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class toggleFlightNoclipCommand extends Command {
		public String getName() {
			return "toggleFlightNoclip";
		}
		public String[] getAliases() {
			return new String[]{"flightNoclip"};
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "切换飞行穿墙模式。启用时，您在播放歌曲时飞行可以穿过方块。";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				Config.getConfig().flightNoclip = !Config.getConfig().flightNoclip;
				if (Config.getConfig().flightNoclip) {
					Util.showChatMessage("§6已启用飞行穿墙模式");
				}
				else {
					Util.showChatMessage("§6已禁用飞行穿墙模式");
				}
				Config.saveConfigWithErrorHandling();
				return true;
			}
			else {
				return false;
			}
		}
	}

	private static class songItemCommand extends Command {
		public String getName() {
			return "songItem";
		}
		public String[] getAliases() {
			return new String[]{"item"};
		}
		public String[] getSyntax() {
			return new String[]{
					"create <song or url>",
					"setSongName <name>",
			};
		}
		public String getDescription() {
			return "为手中物品分配/编辑歌曲数据";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				return false;
			}

			if (MC.interactionManager.getCurrentGameMode() != GameMode.CREATIVE) {
				Util.showChatMessage("§c您必须处于创造模式才能使用此命令");
				return true;
			}

			ItemStack stack = MC.player.getMainHandStack();

			String[] split = args.split(" ");
			switch (split[0].toLowerCase(Locale.ROOT)) {
				case "create":
					if (split.length < 2) return false;
					String location = String.join(" ", Arrays.copyOfRange(split, 1, split.length));
					try {
						(new SongItemCreatorThread(location)).start();
					} catch (IOException e) {
						Util.showChatMessage("§c创建歌曲物品时出错: §4" + e.getMessage());
					}
					return true;
				case "setsongname":
					if (split.length < 2) return false;

					if (SongItemUtils.isSongItem(stack)) {
						String name = String.join(" ", Arrays.copyOfRange(split, 1, split.length));
						SongItemUtils.updateSongItemTag(stack, (songItemTag) -> songItemTag.putString(SongItemUtils.DISPLAY_NAME_KEY, name));
						SongItemUtils.addSongItemDisplay(stack);
						MC.player.setStackInHand(Hand.MAIN_HAND, stack);
						MC.interactionManager.clickCreativeStack(MC.player.getStackInHand(Hand.MAIN_HAND), 36 + MC.player.getInventory().getSelectedSlot());
						Util.showChatMessage("§6已将歌曲显示名称设置为 §3" + name);
						return true;
					} else {
						Util.showChatMessage("§c您必须手持一个歌曲物品");
						return true;
					}
				default:
					return false;
			}
		}
		public CompletableFuture<Suggestions> getSuggestions(String args, SuggestionsBuilder suggestionsBuilder) {
			String[] split = args.split(" ", -1);
			if (split.length <= 1) {
				return CommandSource.suggestMatching(new String[]{
						"create",
						"setSongName",
				}, suggestionsBuilder);
			}
			switch (split[0].toLowerCase(Locale.ROOT)) {
				case "create":
					if (split.length >= 2) {
						String location = String.join(" ", Arrays.copyOfRange(split, 1, split.length));
						return Util.giveSongSuggestions(location, suggestionsBuilder);
					}
				case "setsongname":
				default:
					return null;
			}
		}
	}

	private static class testSongCommand extends Command {
		public String getName() {
			return "testSong";
		}
		public String[] getSyntax() {
			return new String[0];
		}
		public String getDescription() {
			return "创建一首用于测试的歌曲";
		}
		public boolean processCommand(String args) {
			if (args.length() == 0) {
				Song song = new Song("test_song");
				for (int i=0; i<400; i++) {
					song.add(new Note(i, i*50));
				}
				song.length = 400*50;
				SongHandler.getInstance().setSong(song);
				return true;
			}
			else {
				return false;
			}
		}
	}

	public static CompletableFuture<Suggestions> handleSuggestions(String text, SuggestionsBuilder suggestionsBuilder) {
		if (!text.contains(" ")) {
			List<String> names = commandCompletions
					.stream()
					.map((commandName) -> Config.getConfig().prefix+commandName)
					.collect(Collectors.toList());
			return CommandSource.suggestMatching(names, suggestionsBuilder);
		} else {
			String[] split = text.split(" ", 2);
			if (split[0].startsWith(Config.getConfig().prefix)) {
				String commandName = split[0].substring(1).toLowerCase(Locale.ROOT);
				if (commandMap.containsKey(commandName)) {
					return commandMap.get(commandName).getSuggestions(split.length == 1 ? "" : split[1], suggestionsBuilder);
				}
			}
			return null;
		}
	}
}
