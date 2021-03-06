package com.openrsc.server;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.openrsc.server.constants.Constants;
import com.openrsc.server.content.achievement.AchievementSystem;
import com.openrsc.server.database.impl.mysql.MySqlGameDatabase;
import com.openrsc.server.database.GameLogger;
import com.openrsc.server.event.custom.MonitoringEvent;
import com.openrsc.server.event.rsc.GameTickEvent;
import com.openrsc.server.event.rsc.SingleTickEvent;
import com.openrsc.server.event.rsc.impl.combat.scripts.CombatScriptLoader;
import com.openrsc.server.external.EntityHandler;
import com.openrsc.server.model.Point;
import com.openrsc.server.model.entity.Mob;
import com.openrsc.server.model.entity.npc.Npc;
import com.openrsc.server.model.entity.player.Player;
import com.openrsc.server.model.world.World;
import com.openrsc.server.model.world.region.TileValue;
import com.openrsc.server.net.*;
import com.openrsc.server.plugins.PluginHandler;
import com.openrsc.server.util.NamedThreadFactory;
import com.openrsc.server.util.rsc.CollisionFlag;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.logging.log4j.util.Unbox.box;

public class Server implements Runnable {

	/**
	 * The asynchronous logger.
	 */
	private static final Logger LOGGER;

	private final GameStateUpdater gameUpdater;
	private final GameEventHandler gameEventHandler;
	private final DiscordService discordService;
	private final GameTickEvent monitoring;
	private final LoginExecutor loginExecutor;
	private final ServerConfiguration config;
	private final ScheduledExecutorService scheduledExecutor;
	private final PluginHandler pluginHandler;
	private final CombatScriptLoader combatScriptLoader;
	private final GameLogger gameLogger;
	private final EntityHandler entityHandler;
	private final MySqlGameDatabase database;
	private final AchievementSystem achievementSystem;
	private final Constants constants;
	private final RSCPacketFilter packetFilter;

	private final World world;
	private final String name;

	private GameTickEvent updateEvent;
	private ChannelFuture serverChannel;

	private volatile Boolean running = false;
	private volatile boolean initialized = false;

	private long serverStartedTime = 0;
	private long lastIncomingPacketsDuration = 0;
	private long lastGameStateDuration = 0;
	private long lastEventsDuration = 0;
	private long lastOutgoingPacketsDuration = 0;
	private long lastTickDuration = 0;
	private long timeLate = 0;
	private long lastClientUpdate = 0;

	/*Used for pathfinding view debugger
	JPanel2 panel = new JPanel2();
	JFrame frame = new JFrame();
	javax.swing.GroupLayout layout = new javax.swing.GroupLayout(panel);
	*/

	static {
		try {
			Thread.currentThread().setName("InitThread");
			System.setProperty("log4j.configurationFile", "conf/server/log4j2.xml");
			/* Enables asynchronous, garbage-free logging. */
			System.setProperty("Log4jContextSelector",
				"org.apache.logging.log4j.core.async.AsyncLoggerContextSelector");

			LOGGER = LogManager.getLogger();
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	public static final Server startServer(final String confName) throws IOException {
		final long startTime = System.currentTimeMillis();
		Server server = new Server(confName);

		if(!server.isRunning()) {
			server.start();
		}
		final long endTime = System.currentTimeMillis();

		final long bootTime = (long) Math.ceil((double)(endTime - startTime) / 1000.0);

		LOGGER.info(server.getName() + " started in " + bootTime + "s");

		return server;
	}

	public static void main(String[] args){
		LOGGER.info("Launching Game Server...");

		if (args.length == 0) {
			LOGGER.info("Server Configuration file not provided. Loading from default.conf or local.conf.");

			try {
				startServer("default.conf");
			} catch (Throwable t) {
				LOGGER.catching(t);
			}
		} else {
			for (int i = 0; i < args.length; i++) {
				try {
					startServer(args[i]);
				} catch (Throwable t) {
					LOGGER.catching(t);
				}
			}
		}
	}

	public Server(final String configFile) throws IOException {
		config = new ServerConfiguration();
		getConfig().initConfig(configFile);
		LOGGER.info("Server configuration loaded: " + configFile);

		name = getConfig().SERVER_NAME;

		packetFilter = new RSCPacketFilter(this);

		pluginHandler = new PluginHandler(this);
		combatScriptLoader = new CombatScriptLoader(this);
		constants = new Constants(this);
		database = new MySqlGameDatabase(this);

		discordService = new DiscordService(this);
		loginExecutor = new LoginExecutor(this);
		world = new World(this);
		gameEventHandler = new GameEventHandler(this);
		gameUpdater = new GameStateUpdater(this);
		gameLogger = new GameLogger(this);
		entityHandler = new EntityHandler(this);
		achievementSystem = new AchievementSystem(this);
		monitoring = new MonitoringEvent(getWorld());
		scheduledExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat(getName()+" : GameThread").build());
	}

	private void initialize() {
		try {
			// TODO: We need an uninitialize process. Unloads all of these classes. When this is written the initialize() method should synchronize on initialized like run does with running.

			/*Used for pathfinding view debugger
			if (PathValidation.DEBUG) {
				panel.setLayout(layout);
				frame.add(panel);
				frame.setSize(600, 600);
				frame.setVisible(true);
			}*/

			LOGGER.info("Connecting to Database...");
			try {
				getDatabase().open();
			} catch (final Exception ex) {
				LOGGER.catching(ex);
				System.exit(1);
			}
			LOGGER.info("\t Database Connection Completed");

			LOGGER.info("Loading Game Definitions...");
			getEntityHandler().load();
			LOGGER.info("\t Definitions Completed");

			LOGGER.info("Loading Plugins...");
			getPluginHandler().load();
			LOGGER.info("\t Plugins Completed");

			LOGGER.info("Loading Combat Scripts...");
			getCombatScriptLoader().load();
			LOGGER.info("\t Combat Scripts Completed");

			LOGGER.info("Loading World...");
			getWorld().load();
			LOGGER.info("\t World Completed");

			/*LOGGER.info("Loading Achievements...");
			getAchievementSystem().load();
			LOGGER.info("\t Achievements Completed");*/

			LOGGER.info("Loading profiling monitoring...");
			// Send monitoring info as a game event so that it can be profiled.
			getGameEventHandler().add(monitoring);
			LOGGER.info("Profiling Completed");

			//Never run ResourceLeakDetector PARANOID in production.
			//ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
			final EventLoopGroup bossGroup = new NioEventLoopGroup(0, new NamedThreadFactory(getName() + " : IOBossThread"));
			final EventLoopGroup workerGroup = new NioEventLoopGroup(0, new NamedThreadFactory(getName() + " : IOWorkerThread"));
			final ServerBootstrap bootstrap = new ServerBootstrap();
			final Server gameServer = this;

			bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).childHandler(
				new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(final SocketChannel channel) {
						final ChannelPipeline pipeline = channel.pipeline();
						pipeline.addLast("decoder", new RSCProtocolDecoder());
						pipeline.addLast("encoder", new RSCProtocolEncoder());
						pipeline.addLast("handler", new RSCConnectionHandler(gameServer));
					}
				}
			);

			bootstrap.childOption(ChannelOption.TCP_NODELAY, true);
			bootstrap.childOption(ChannelOption.SO_KEEPALIVE, false);
			bootstrap.childOption(ChannelOption.SO_RCVBUF, 10000);
			bootstrap.childOption(ChannelOption.SO_SNDBUF, 10000);
			try {
				getPluginHandler().handlePlugin(getWorld(), "Startup", new Object[]{});
				serverChannel = bootstrap.bind(new InetSocketAddress(getConfig().SERVER_PORT)).sync();
				LOGGER.info("Game world is now online on port {}!", box(getConfig().SERVER_PORT));
			} catch (final InterruptedException e) {
				LOGGER.catching(e);
			}

			initialized = true;

		} catch (Throwable t) {
			LOGGER.catching(t);
			System.exit(1);
		}

		lastClientUpdate = System.currentTimeMillis();
	}

	public void start() {
		synchronized (running) {
			if (!isInitialized()) {
				initialize();
			}

			running = true;
			serverStartedTime = System.currentTimeMillis();
			scheduledExecutor.scheduleAtFixedRate(this, 0, 10, TimeUnit.MILLISECONDS);

			loginExecutor.start();
			discordService.start();
			gameLogger.start();
		}
	}

	public void stop() {
		synchronized (running) {
			running = false;
			scheduledExecutor.shutdown();

			loginExecutor.stop();
			discordService.stop();
			gameLogger.stop();
		}
	}

	public void kill() {
		synchronized (running) {
			// TODO: Uninitialize server
			stop();
			LOGGER.fatal(getName() + " shutting down...");
			System.exit(0);
		}
	}

	private void unbind() {
		try {
			serverChannel.channel().disconnect();
		} catch (Exception exception) {
			LOGGER.catching(exception);
		}
	}

	public void run() {
		synchronized (running) {
			try {
				timeLate = System.currentTimeMillis() - lastClientUpdate - getConfig().GAME_TICK;
				if (getTimeLate() >= 0) {
					lastClientUpdate += getConfig().GAME_TICK;

					// Doing the set in two stages here such that the whole tick has access to the same values for profiling information.
					final long tickStart = System.currentTimeMillis();
					final long lastIncomingPacketsDuration = processIncomingPackets();
					final long lastEventsDuration = runGameEvents();
					final long lastGameStateDuration = runGameStateUpdate();
					final long lastOutgoingPacketsDuration = processOutgoingPackets();
					final long tickEnd = System.currentTimeMillis();
					final long lastTickDuration = tickEnd - tickStart;

					this.lastIncomingPacketsDuration = lastIncomingPacketsDuration;
					this.lastEventsDuration = lastEventsDuration;
					this.lastGameStateDuration = lastGameStateDuration;
					this.lastOutgoingPacketsDuration = lastOutgoingPacketsDuration;
					this.lastTickDuration = lastTickDuration;
				} else {
					if (getConfig().WANT_CUSTOM_WALK_SPEED) {
						for (Player p : getWorld().getPlayers()) {
							p.updatePosition();
						}

						for (Npc n : getWorld().getNpcs()) {
							n.updatePosition();
						}

						getGameUpdater().executeWalkToActions();
					}
				}
			} catch (Throwable t) {
				LOGGER.catching(t);
			}
			//if (PathValidation.DEBUG)
			//	panel.repaint();
		}
	}

	protected final long runGameEvents() {
		return getGameEventHandler().runGameEvents();
	}

	protected final long runGameStateUpdate() throws Exception {
		return getGameUpdater().doUpdates();
	}

	protected final long processIncomingPackets() {
		final long processPacketsStart	= System.currentTimeMillis();
		for (Player p : getWorld().getPlayers()) {
			p.processIncomingPackets();
		}
		final long processPacketsEnd = System.currentTimeMillis();

		return processPacketsEnd - processPacketsStart;
	}

	protected long processOutgoingPackets() {
		final long processPacketsStart	= System.currentTimeMillis();
		for (Player p : getWorld().getPlayers()) {
			p.processOutgoingPackets();
		}
		final long processPacketsEnd = System.currentTimeMillis();

		return processPacketsEnd - processPacketsStart;
	}

	public boolean shutdownForUpdate(int seconds) {
		if (updateEvent != null) {
			return false;
		}
		updateEvent = new SingleTickEvent(getWorld(), null, (seconds - 1) * 1000 / getConfig().GAME_TICK, "Shutdown for Update") {
			public void action() {
				unbind();
				saveAndShutdown();
			}
		};
		getGameEventHandler().add(updateEvent);
		return true;
	}

	private void saveAndShutdown() {
		LOGGER.info("Saving players for shutdown...");
		getWorld().getClanManager().saveClans();
		for (Player p : getWorld().getPlayers()) {
			p.unregister(true, "Server shutting down.");
		}
		LOGGER.info("Players saved...");

		SingleTickEvent up = new SingleTickEvent(getWorld(), null, 10, "Save and Shutdown") {
			public void action() {
				kill();
				try {
					getDatabase().close();
				} catch (final Exception ex) {
					LOGGER.catching(ex);
				}
			}
		};
		getGameEventHandler().add(up);
	}

	public boolean restart(int seconds) {
		if (updateEvent != null) {
			return false;
		}
		updateEvent = new SingleTickEvent(getWorld(), null, (seconds - 1) * 1000 / getConfig().GAME_TICK, "Restart") {
			public void action() {
				unbind();
				//saveAndRestart();
				saveAndShutdown();
			}
		};
		getGameEventHandler().add(updateEvent);
		return true;
	}

	public long timeTillShutdown() {
		if (updateEvent == null) {
			return -1;
		}
		return updateEvent.timeTillNextRun();
	}

	public final long getLastGameStateDuration() {
		return lastGameStateDuration;
	}

	public final long getLastEventsDuration() {
		return lastEventsDuration;
	}

	public final long getLastTickDuration() {
		return lastTickDuration;
	}

	public final GameEventHandler getGameEventHandler() {
		return gameEventHandler;
	}

	public final GameStateUpdater getGameUpdater() {
		return gameUpdater;
	}

	public final DiscordService getDiscordService() {
		return discordService;
	}

	public final LoginExecutor getLoginExecutor() {
		return loginExecutor;
	}

	public final RSCPacketFilter getPacketFilter() {
		return packetFilter;
	}

	public final long getLastIncomingPacketsDuration() {
		return lastIncomingPacketsDuration;
	}

	public final long getLastOutgoingPacketsDuration() {
		return lastOutgoingPacketsDuration;
	}

	public final long getTimeLate() {
		return timeLate;
	}

	public final long getServerStartedTime() {
		return serverStartedTime;
	}

	public final long getCurrentTick() {
		return (System.currentTimeMillis() - getServerStartedTime()) / getConfig().GAME_TICK;
	}

	public void skipTicks(final long ticks) {
		lastClientUpdate += ticks * getConfig().GAME_TICK;
	}

	public final ServerConfiguration getConfig() {
		return config;
	}

	public final boolean isInitialized() {
		return initialized;
	}

	public final boolean isRunning() {
		return running;
	}

	public final Constants getConstants() {
		return constants;
	}

	public synchronized World getWorld() {
		return world;
	}

	public String getName() {
		return name;
	}

	public PluginHandler getPluginHandler() {
		return pluginHandler;
	}

	public CombatScriptLoader getCombatScriptLoader() {
		return combatScriptLoader;
	}

	public GameLogger getGameLogger() {
		return gameLogger;
	}

	public EntityHandler getEntityHandler() {
		return entityHandler;
	}

	public MySqlGameDatabase getDatabase() {
		return database;
	}

	public AchievementSystem getAchievementSystem() {
		return achievementSystem;
	}

	class JPanel2 extends JPanel {
		int size = 15;
		int width = 20;
		Color[][] board = null;

		JPanel2() {
			// set a preferred size for the custom panel.
			setPreferredSize(new Dimension(420, 420));
		}

		private void setTile(int x, int y, Color color) {
			if (x < 0 || x >= 2 * size + 1)
				return;
			if (y < 0 || y >= 2 * size + 1)
				return;
			this.board[x][y] = color;
		}
		private void drawBorder(int x, int y, Graphics g) {
			g.setColor(Color.black);
			g.drawRect(x * width, y * width, width, width);
		}

		private void drawBlocks(int x, int y, TileValue tile, Graphics g) {
			x *= width;
			y *= width;
			if ((tile.traversalMask & (CollisionFlag.FULL_BLOCK_A | CollisionFlag.FULL_BLOCK_B | CollisionFlag.FULL_BLOCK_C)) != 0) {
				g.fillRect(x,y,width,width);
				return;
			}
			g.setColor(Color.red);
			if ((tile.traversalMask & CollisionFlag.EAST_BLOCKED) != 0) {
				g.fillRect(x+width-4,y+1,3,width);
			}
			if ((tile.traversalMask & CollisionFlag.WEST_BLOCKED) != 0) {
				g.fillRect(x+1,y+1,3,width);
			}
			if ((tile.traversalMask & CollisionFlag.NORTH_BLOCKED) != 0) {
				g.fillRect(x,y+1,width,3);
			}
			if ((tile.traversalMask & CollisionFlag.SOUTH_BLOCKED) != 0) {
				g.fillRect(x,y+width-4,width,3);
			}
		}

		private void drawPath(Mob mob, Graphics g) {
			if (mob.getWalkingQueue() != null && mob.getWalkingQueue().path != null && mob.getWalkingQueue().path.size() > 0) {
				Iterator<Point> path = mob.getWalkingQueue().path.iterator();
				if (mob.isPlayer()) {
					g.setColor(Color.BLUE);
				}
				else {
					g.setColor(Color.ORANGE);
				}
				while (path.hasNext()) {
					Point next = path.next();
					if (mob.isPlayer())
						g.fillRect(((mob.getX()+size)-next.getX())*width,(next.getY() - (mob.getY()-size))*width,width,width);
					else
						g.fillRect(((((Npc)mob).getBehavior().getChaseTarget().getX()+size)-next.getX())*width,(next.getY() - (((Npc)mob).getBehavior().getChaseTarget().getY()-size))*width,width,width);
				}
			}
		}
		@Override
		public void paintComponent(Graphics g) {
			board = new Color[2 * size + 1][2 * size + 1];
			super.paintComponent(g);
			if (world.getPlayers().size() > 0) {
				Player test = world.getPlayers().get(0);
				int centerx = test.getX();
				int centery = test.getY();

				for (int x = -size; x <= size; x++) {
					for (int y = -size; y <= size; y++) {
						drawBorder(x + size, y+size,g);
						TileValue tile = world.getTile(centerx - x, centery + y);
						if (tile == null) {
							continue;
						}
						drawBlocks(x+size,y+size,tile,g);

					}
				}

				g.setColor(Color.pink);
				g.fillRect(size*width,size*width,width,width);
				drawPath(test, g);
				for (Npc npc : getWorld().getNpcs()) {
					if (npc.isChasing()) {
						g.setColor(Color.red);
						g.fillRect(((centerx+size)-npc.getX())*width,(npc.getY() - (centery-size))*width,width,width);
						drawPath(npc, g);
					}

				}
			}
		}
	}
}
