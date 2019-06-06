/*
 * Copyright (c) 2018, Cameron <https://github.com/noremac201>
 * Copyright (c) 2018, Jacob M <https://github.com/jacoblairm>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.barbarianassault;
import com.google.common.collect.ImmutableList;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Image;
import java.util.*;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import lombok.extern.slf4j.Slf4j;

@Slf4j


@PluginDescriptor(
		name = "Barbarian Assault",
		description = "Show a timer to the next call change and game/wave duration in chat.",
		tags = {"minigame", "overlay", "timer"}
)
public class BarbarianAssaultPlugin extends Plugin
{
	private static final int BA_WAVE_NUM_INDEX = 2;
	private static final String START_WAVE = "1";
	private static final String ENDGAME_REWARD_NEEDLE_TEXT = "<br>5";

	@Getter
	private int collectedEggCount = 0;
	@Getter
	private int positiveEggCount = 0;
	@Getter
	private int wrongEggs = 0;
	@Getter
	private int HpHealed = 0;
	@Getter
	private int totalCollectedEggCount = 0;
	@Getter
	private int totalHpHealed = 0;

	private boolean hasAnnounced;
    private Font font;
	private final Image clockImage = ImageUtil.getResourceStreamFromClass(getClass(), "clock.png");
	private int inGameBit = 0;
	private String currentWave = START_WAVE;
	private GameTimer gameTime;

	@Getter(AccessLevel.PACKAGE)
	private HashMap<WorldPoint, Integer> redEggs;

	@Getter(AccessLevel.PACKAGE)
	private HashMap<WorldPoint, Integer> greenEggs;

	@Getter(AccessLevel.PACKAGE)
	private HashMap<WorldPoint, Integer> blueEggs;

	@Getter(AccessLevel.PACKAGE)
	private HashMap<WorldPoint, Integer> yellowEggs;

	@Inject
	@Getter
	private Client client;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BarbarianAssaultConfig config;

	@Inject
	private BarbarianAssaultOverlay overlay;

	private final ImmutableList<WidgetInfo> WIDGETS = ImmutableList.of(
			WidgetInfo.BA_FAILED_ATTACKER_ATTACKS,
			WidgetInfo.BA_RUNNERS_PASSED,
			WidgetInfo.BA_EGGS_COLLECTED,
			WidgetInfo.BA_HITPOINTS_REPLENISHED,
			WidgetInfo.BA_WRONG_POISON_PACKS,
			WidgetInfo.BA_HONOUR_POINTS_REWARD
	);
	private final ImmutableList<WidgetInfo> POINTSWIDGETS = ImmutableList.of(
			//base
			WidgetInfo.BA_BASE_POINTS,
			//att
			WidgetInfo.BA_FAILED_ATTACKER_ATTACKS_POINTS,
			WidgetInfo.BA_RANGERS_KILLED,
			WidgetInfo.BA_FIGHTERS_KILLED,
			//def
			WidgetInfo.BA_RUNNERS_PASSED_POINTS,
			WidgetInfo.BA_RUNNERS_KILLED,
			//coll
			WidgetInfo.BA_EGGS_COLLECTED_POINTS,
			//heal
			WidgetInfo.BA_HEALERS_KILLED,
			WidgetInfo.BA_HITPOINTS_REPLENISHED_POINTS,
			WidgetInfo.BA_WRONG_POISON_PACKS_POINTS
	);

    @Provides
	BarbarianAssaultConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BarbarianAssaultConfig.class);
	}

	private Game game;
    private Wave wave;

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(overlay);
		font = FontManager.getRunescapeFont()
				.deriveFont(Font.BOLD, 24);
		redEggs = new HashMap<>();
		greenEggs = new HashMap<>();
		blueEggs = new HashMap<>();
		yellowEggs = new HashMap<>();
    }

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		gameTime = null;
		currentWave = START_WAVE;
		inGameBit = 0;
		collectedEggCount = 0;
		positiveEggCount = 0;
		wrongEggs = 0;
		HpHealed = 0;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		switch (event.getGroupId())
		{
			case WidgetID.BA_REWARD_GROUP_ID:
			{
				Widget rewardWidget = client.getWidget(WidgetInfo.BA_REWARD_TEXT);

				if (config.waveTimes() && rewardWidget != null && rewardWidget.getText().contains(ENDGAME_REWARD_NEEDLE_TEXT) && gameTime != null)
				{
					announceTime("Game finished, duration: ", gameTime.getTime(false));
					gameTime = null;
				}
				break;
			}
			case WidgetID.BA_ATTACKER_GROUP_ID:
			{
				setOverlayRound(Role.ATTACKER);
				break;
			}
			case WidgetID.BA_DEFENDER_GROUP_ID:
			{
				setOverlayRound(Role.DEFENDER);
				break;
			}
			case WidgetID.BA_HEALER_GROUP_ID:
			{
				setOverlayRound(Role.HEALER);
				break;
			}
			case WidgetID.BA_COLLECTOR_GROUP_ID:
			{
				setOverlayRound(Role.COLLECTOR);
				break;
			}
		}
	}


	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.GAMEMESSAGE
				&& event.getMessage().startsWith("---- Wave:"))
		{
			String[] message = event.getMessage().split(" ");
			currentWave = message[BA_WAVE_NUM_INDEX];
			if (currentWave.equals(START_WAVE))
			{
				gameTime = new GameTimer();
			}
			else if (gameTime != null)
			{
				gameTime.setWaveStartTime();
			}
		}
	}



	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int inGame = client.getVar(Varbits.IN_GAME_BA);

		if (inGameBit != inGame)
		{
			if (inGameBit == 1)
			{
				overlay.setCurrentRound(null);
				// Use an instance check to determine if this is exiting a game or a tutorial
				// After exiting tutorials there is a small delay before changing IN_GAME_BA back to
				// 0 whereas when in a real wave it changes while still in the instance.
				if (config.waveTimes() && gameTime != null && client.isInInstancedRegion())
				{
					announceTime("Wave " + currentWave + " duration: ", gameTime.getTime(true));
				}
			}
			else
			{
				hasAnnounced = false;
			}
		}

		inGameBit = inGame;
	}
	private void setOverlayRound(Role role)
	{
		// Prevent changing roles when a role is already set, as widgets can be
		// loaded multiple times in game from eg. opening and closing the horn
		// of glory.
		if (overlay.getCurrentRound() != null)
		{
			return;
		}

		overlay.setCurrentRound(new Round(role));
	}


	@Subscribe
	public void onItemSpawned(ItemSpawned itemSpawned)
	{
		int itemId = itemSpawned.getItem().getId();
		WorldPoint worldPoint = itemSpawned.getTile().getWorldLocation();
		HashMap<WorldPoint, Integer> eggMap = getEggMap(itemId);
		if (eggMap !=  null)
		{
			Integer existingQuantity = eggMap.putIfAbsent(worldPoint, 1);
			if (existingQuantity != null)
			{
				eggMap.put(worldPoint, existingQuantity + 1);
			}
		}
	}


	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.highlightCollectorEggs())
		{
			return;
		}
		if (overlay.getCurrentRound() == null)
		{
			return;
		}
		if (overlay.getCurrentRound().getRoundRole() != Role.COLLECTOR)
		{
			return;
		}

		String calledEgg = getCollectorHeardCall();
		String target = event.getTarget();
		String option = event.getOption();
		String targetClean = target.substring(target.indexOf('>') + 1);
		String optionClean = option.substring(option.indexOf('>') + 1);

		if ("Take".equals(optionClean))
		{
			Color highlightColor = null;

			if (calledEgg != null && calledEgg.startsWith(targetClean))
			{
				highlightColor = getEggColor(targetClean);
			}
			else if ("Yellow egg".equals(targetClean))
			{
				// Always show yellow egg
				highlightColor = Color.YELLOW;
			}

			if (highlightColor != null)
			{
				MenuEntry[] menuEntries = client.getMenuEntries();
				MenuEntry last = menuEntries[menuEntries.length - 1];
				last.setTarget(ColorUtil.prependColorTag(targetClean, highlightColor));
				client.setMenuEntries(menuEntries);
			}
		}
	}

	private void announceSomething(final ChatMessageBuilder chatMessage)
	{
		chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(chatMessage.build())
				.build());
	}

	String getCollectorHeardCall()
	{
		Widget widget = client.getWidget(WidgetInfo.BA_COLL_LISTEN_TEXT);
		String call = null;

		if (widget != null)
		{
			call = widget.getText();
		}

		return call;
	}

	Map<WorldPoint, Integer> getCalledEggMap()
	{
		Map<WorldPoint, Integer> map;
		String calledEgg = getCollectorHeardCall();

		if (calledEgg == null)
		{
			return null;
		}

		switch (calledEgg)
		{
			case "Red eggs":
				map = redEggs;
				break;
			case "Green eggs":
				map = greenEggs;
				break;
			case "Blue eggs":
				map = blueEggs;
				break;
			default:
				map = null;
		}

		return map;
	}

	static Color getEggColor(String str)
	{
		Color color;

		if (str == null)
		{
			return null;
		}

		if (str.startsWith("Red"))
		{
			color = Color.RED;
		}
		else if (str.startsWith("Green"))
		{
			color = Color.GREEN;
		}
		else if (str.startsWith("Blue"))
		{
			color = Color.CYAN;
		}
		else if (str.startsWith("Yellow"))
		{
			color = Color.YELLOW;
		}
		else
		{
			color = null;
		}

		return color;
	}

	private HashMap<WorldPoint, Integer> getEggMap(int itemID)
	{
		switch (itemID)
		{
			case ItemID.RED_EGG:
				return redEggs;
			case ItemID.GREEN_EGG:
				return greenEggs;
			case ItemID.BLUE_EGG:
				return blueEggs;
			case ItemID.YELLOW_EGG:
				return yellowEggs;
			default:
				return null;
		}
	}


	private void announceTime(String preText, String time)
	{
		final String chatMessage = new ChatMessageBuilder()
				.append(ChatColorType.NORMAL)
				.append(preText)
				.append(ChatColorType.HIGHLIGHT)
				.append(time)
				.build();

		chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(chatMessage)
				.build());
	}

	private boolean isEgg(int itemID)
	{
		if (itemID == ItemID.RED_EGG || itemID == ItemID.GREEN_EGG
				|| itemID == ItemID.BLUE_EGG || itemID == ItemID.YELLOW_EGG)
		{
			return true;
		}
		return false;
	}

	private boolean isUnderPlayer(Tile tile)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return false;
		}

		return (tile.getWorldLocation().equals(local.getWorldLocation()));
	}

	public Font getFont()
	{
		return font;
	}

	public Image getClockImage()
	{
		return clockImage;
	}

	public int getListenItemId(WidgetInfo listenInfo)
	{
		Widget listenWidget = client.getWidget(listenInfo);

		if (listenWidget != null)
		{
			switch (listenWidget.getText())
			{
				case "Tofu":
					return ItemID.TOFU;
				case "Crackers":
					return ItemID.CRACKERS;
				case "Worms":
					return ItemID.WORMS;
				case "Pois. Worms":
					return ItemID.POISONED_WORMS;
				case "Pois. Tofu":
					return ItemID.POISONED_TOFU;
				case "Pois. Meat":
					return ItemID.POISONED_MEAT;
			}
		}

		return -1;
	}
}