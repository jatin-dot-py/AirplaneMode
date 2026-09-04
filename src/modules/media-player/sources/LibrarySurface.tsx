import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Image,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import {
  addItemsToLocalPlaylist,
  cancelDownload,
  createLocalPlaylist,
  deleteLocalPlaylist,
  listLocalPlaylists,
  listMediaItems,
  onDownloadStateChanged,
  playMedia,
  removeItemsFromLocalPlaylist,
  removeLibraryItems,
  retryDownload,
  setLocalPlaylistPinned,
  type LocalPlaylist,
  type MediaAvailability,
  type MediaItem,
  type PlaybackState,
} from '../../../native/MediaEngine';
import {colors, radii, spacing, typography} from '../../../theme';
import MediaArtwork from '../MediaArtwork';

const fallbackArtwork = require('../../../assets/icons/music-note.png');
const activeStates = new Set<MediaAvailability>([
  'waiting_for_resolver',
  'queued',
  'downloading',
]);

type SourceFilter = 'all' | MediaItem['source'];

const filters: ReadonlyArray<{id: SourceFilter; label: string}> = [
  {id: 'all', label: 'All media'},
  {id: 'youtube-music', label: 'YouTube Music'},
  {id: 'youtube', label: 'YouTube'},
  {id: 'gallery', label: 'Gallery'},
];

function LibrarySurface({
  onNotice,
  playback,
  query,
  revision,
}: {
  onNotice: (message: string) => void;
  playback: PlaybackState;
  query: string;
  revision: number;
}) {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [playlists, setPlaylists] = useState<LocalPlaylist[]>([]);
  const [filter, setFilter] = useState<SourceFilter>('all');
  const [activePlaylistId, setActivePlaylistId] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [menuItem, setMenuItem] = useState<MediaItem | null>(null);
  const [playlistMenu, setPlaylistMenu] = useState<LocalPlaylist | null>(null);
  const [playlistPickerIds, setPlaylistPickerIds] = useState<string[] | null>(null);
  const [newPlaylistName, setNewPlaylistName] = useState('');
  const [filterMenuOpen, setFilterMenuOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const [nextItems, nextPlaylists] = await Promise.all([
      listMediaItems('all'),
      listLocalPlaylists(),
    ]);
    setItems(nextItems);
    setPlaylists(nextPlaylists);
  }, []);

  useEffect(() => {
    let cancelled = false;
    refresh()
      .catch(() => {
        if (!cancelled) setItems([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [refresh, revision]);

  useEffect(() => {
    const subscription = onDownloadStateChanged(update => {
      setItems(current => current.map(item => item.id === update.itemId
        ? {
            ...item,
            availability: update.state,
            downloadProgress: update.progress,
          }
        : item));
    });
    return () => subscription?.remove();
  }, []);

  useEffect(() => {
    if (activePlaylistId && !playlists.some(value => value.id === activePlaylistId)) {
      setActivePlaylistId(null);
    }
  }, [activePlaylistId, playlists]);

  const activePlaylist = playlists.find(value => value.id === activePlaylistId) ?? null;
  const visibleItems = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    const playlistIds = activePlaylist ? new Set(activePlaylist.mediaItemIds) : null;
    return items
      .filter(item => filter === 'all' || item.source === filter)
      .filter(item => !playlistIds || playlistIds.has(item.id))
      .filter(item => !needle || [
        item.title,
        item.artist,
        item.collectionName,
        sourceName(item.source),
      ].some(value => value?.toLocaleLowerCase().includes(needle)))
      .sort((left, right) => {
        if (activePlaylist) {
          return activePlaylist.mediaItemIds.indexOf(left.id) -
            activePlaylist.mediaItemIds.indexOf(right.id);
        }
        const leftRank = downloadSortRank(left.availability);
        const rightRank = downloadSortRank(right.availability);
        return rightRank - leftRank || right.updatedAt - left.updatedAt;
      });
  }, [activePlaylist, filter, items, query]);

  const selectionMode = selectedIds.size > 0;
  const toggleSelected = useCallback((id: string) => {
    setSelectedIds(current => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const run = useCallback(async (action: () => Promise<unknown>, message?: string) => {
    if (busy) return;
    setBusy(true);
    try {
      await action();
      await refresh();
      if (message) onNotice(message);
    } catch (reason) {
      onNotice(reason instanceof Error ? reason.message : 'That action could not be completed');
    } finally {
      setBusy(false);
    }
  }, [busy, onNotice, refresh]);

  const playItem = useCallback(async (item: MediaItem) => {
    try {
      await playMedia(item.id, activePlaylist?.id ?? null);
    } catch (reason) {
      onNotice(reason instanceof Error ? reason.message : 'This item could not be played');
    }
  }, [activePlaylist?.id, onNotice]);

  const primaryAction = useCallback((item: MediaItem) => {
    if (item.availability === 'ready' && item.playbackLocator) {
      return playItem(item);
    }
    if (['failed', 'missing', 'cancelled'].includes(item.availability)) {
      return run(() => retryDownload(item.id), 'Download queued');
    }
    return Promise.resolve();
  }, [playItem, run]);

  const confirmCancelDownload = useCallback((item: MediaItem) => {
    setMenuItem(null);
    Alert.alert(
      'Cancel download?',
      `${item.title} will stay in your library and can be downloaded again later.`,
      [
        {text: 'Keep downloading', style: 'cancel'},
        {
          text: 'Cancel download',
          style: 'destructive',
          onPress: () => run(() => cancelDownload(item.id), 'Download cancelled'),
        },
      ],
    );
  }, [run]);

  const removeItems = useCallback((ids: string[]) => {
    if (!ids.length) return;
    Alert.alert(
      ids.length === 1 ? 'Remove from library?' : `Remove ${ids.length} items?`,
      'Downloaded files and AirplaneMode artwork will also be deleted. Gallery originals stay untouched.',
      [
        {text: 'Keep', style: 'cancel'},
        {
          text: 'Remove',
          style: 'destructive',
          onPress: () => {
            setMenuItem(null);
            run(() => removeLibraryItems(ids), `${ids.length} ${ids.length === 1 ? 'item' : 'items'} removed`)
              .then(() => setSelectedIds(new Set<string>()));
          },
        },
      ],
    );
  }, [run]);

  const addToPlaylist = useCallback((playlist: LocalPlaylist) => {
    const ids = playlistPickerIds ?? [];
    return run(
      () => addItemsToLocalPlaylist(playlist.id, ids),
      `Added to ${playlist.name}`,
    ).then(() => {
      setPlaylistPickerIds(null);
      setSelectedIds(new Set<string>());
      setMenuItem(null);
    });
  }, [playlistPickerIds, run]);

  const createPlaylist = useCallback(() => {
    const name = newPlaylistName.trim();
    if (!name) return;
    const ids = playlistPickerIds ?? [];
    run(() => createLocalPlaylist(name, ids), `${name} created`).then(() => {
      setNewPlaylistName('');
      setPlaylistPickerIds(null);
      setSelectedIds(new Set<string>());
      setMenuItem(null);
    });
  }, [newPlaylistName, playlistPickerIds, run]);

  if (loading) {
    return <View style={styles.centered}><ActivityIndicator color={colors.text} /></View>;
  }

  return (
    <View style={styles.container}>
      <FlatList
        contentContainerStyle={visibleItems.length ? styles.list : styles.emptyList}
        data={visibleItems}
        extraData={[playback.mediaId, playback.isPlaying, selectedIds]}
        keyboardShouldPersistTaps="handled"
        keyExtractor={item => item.id}
        ListEmptyComponent={(
          <EmptyLibrary
            filtered={filter !== 'all' || Boolean(activePlaylist) || Boolean(query.trim())}
          />
        )}
        ListHeaderComponent={(
          <LibraryHeader
            activePlaylist={activePlaylist}
            filter={filter}
            itemCount={visibleItems.length}
            onCreatePlaylist={() => {
              setNewPlaylistName('');
              setPlaylistPickerIds([]);
            }}
            onFilterPress={() => setFilterMenuOpen(true)}
            onPlaylist={playlist => setActivePlaylistId(
              current => current === playlist.id ? null : playlist.id,
            )}
            onPlaylistMenu={setPlaylistMenu}
            playlists={playlists}
          />
        )}
        renderItem={({item}) => (
          <MediaRow
            current={item.id === playback.mediaId}
            item={item}
            playing={item.id === playback.mediaId && playback.isPlaying}
            selected={selectedIds.has(item.id)}
            selectionMode={selectionMode}
            onLongPress={() => toggleSelected(item.id)}
            onMenu={() => setMenuItem(item)}
            onPress={() => {
              if (selectionMode) toggleSelected(item.id);
              else if (activeStates.has(item.availability)) setMenuItem(item);
              else primaryAction(item);
            }}
          />
        )}
        showsVerticalScrollIndicator={false}
      />

      {selectionMode ? (
        <SelectionBar
          count={selectedIds.size}
          onAdd={() => setPlaylistPickerIds([...selectedIds])}
          onClose={() => setSelectedIds(new Set<string>())}
          onDelete={() => removeItems([...selectedIds])}
        />
      ) : null}

      <ItemActionSheet
        activePlaylist={activePlaylist}
        item={menuItem}
        onAdd={() => menuItem && setPlaylistPickerIds([menuItem.id])}
        onCancel={() => menuItem && confirmCancelDownload(menuItem)}
        onClose={() => setMenuItem(null)}
        onPrimary={() => menuItem && primaryAction(menuItem).then(() => setMenuItem(null))}
        onRemove={() => menuItem && removeItems([menuItem.id])}
        onRemoveFromPlaylist={() => {
          if (!menuItem || !activePlaylist) return;
          run(
            () => removeItemsFromLocalPlaylist(activePlaylist.id, [menuItem.id]),
            `Removed from ${activePlaylist.name}`,
          ).then(() => setMenuItem(null));
        }}
      />

      <PlaylistPicker
        busy={busy}
        ids={playlistPickerIds}
        name={newPlaylistName}
        onAdd={addToPlaylist}
        onChangeName={setNewPlaylistName}
        onClose={() => {
          setPlaylistPickerIds(null);
          setNewPlaylistName('');
        }}
        onCreate={createPlaylist}
        playlists={playlists}
      />

      <PlaylistActionSheet
        playlist={playlistMenu}
        onClose={() => setPlaylistMenu(null)}
        onDelete={() => playlistMenu && Alert.alert(
          `Delete ${playlistMenu.name}?`,
          'The media stays in your library.',
          [
            {text: 'Keep', style: 'cancel'},
            {
              text: 'Delete',
              style: 'destructive',
              onPress: () => run(
                () => deleteLocalPlaylist(playlistMenu.id),
                'Playlist deleted',
              ).then(() => setPlaylistMenu(null)),
            },
          ],
        )}
        onPin={() => playlistMenu && run(
          () => setLocalPlaylistPinned(playlistMenu.id, !playlistMenu.pinned),
        ).then(() => setPlaylistMenu(null))}
      />

      <FilterSheet
        current={filter}
        onClose={() => setFilterMenuOpen(false)}
        onSelect={value => {
          setFilter(value);
          setFilterMenuOpen(false);
        }}
        visible={filterMenuOpen}
      />
    </View>
  );
}

function LibraryHeader({
  activePlaylist,
  filter,
  itemCount,
  onCreatePlaylist,
  onFilterPress,
  onPlaylist,
  onPlaylistMenu,
  playlists,
}: {
  activePlaylist: LocalPlaylist | null;
  filter: SourceFilter;
  itemCount: number;
  onCreatePlaylist: () => void;
  onFilterPress: () => void;
  onPlaylist: (playlist: LocalPlaylist) => void;
  onPlaylistMenu: (playlist: LocalPlaylist) => void;
  playlists: LocalPlaylist[];
}) {
  const currentFilter = filters.find(value => value.id === filter) ?? filters[0];
  return (
    <View>
      <View style={styles.playlistTitleRow}>
        <Text style={styles.sectionTitle}>Playlists</Text>
        {activePlaylist ? (
          <Text numberOfLines={1} style={styles.activePlaylistName}>{activePlaylist.name}</Text>
        ) : null}
      </View>
      <ScrollView
        contentContainerStyle={styles.playlistList}
        horizontal
        showsHorizontalScrollIndicator={false}>
        <Pressable
          accessibilityLabel="Create playlist"
          onPress={onCreatePlaylist}
          style={({pressed}) => [styles.playlistCard, pressed && styles.pressed]}>
          <View style={styles.newPlaylistArtwork}>
            <Text style={styles.plus}>＋</Text>
          </View>
          <Text numberOfLines={1} style={styles.playlistName}>New playlist</Text>
          <Text style={styles.playlistCount}>Create</Text>
        </Pressable>
        {playlists.map(playlist => (
          <Pressable
            accessibilityLabel={playlist.name}
            accessibilityState={{selected: activePlaylist?.id === playlist.id}}
            key={playlist.id}
            onLongPress={() => onPlaylistMenu(playlist)}
            onPress={() => onPlaylist(playlist)}
            style={({pressed}) => [styles.playlistCard, pressed && styles.pressed]}>
            <PlaylistArtwork
              paths={playlist.artworkPaths}
              selected={activePlaylist?.id === playlist.id}
            />
            <Text
              numberOfLines={1}
              style={[
                styles.playlistName,
                activePlaylist?.id === playlist.id && styles.playlistNameActive,
              ]}>
              {playlist.name}
            </Text>
            <Text style={styles.playlistCount}>{playlist.itemCount} tracks</Text>
          </Pressable>
        ))}
      </ScrollView>

      <View style={styles.libraryControlRow}>
        <Pressable
          accessibilityLabel={`Filter library, currently ${currentFilter.label}`}
          accessibilityRole="button"
          onPress={onFilterPress}
          style={({pressed}) => [styles.filterButton, pressed && styles.pressed]}>
          <Text style={styles.filterButtonText}>{currentFilter.label}</Text>
          <Text style={styles.filterChevron}>⌄</Text>
        </Pressable>
        <Text style={styles.itemCount}>{itemCount} {itemCount === 1 ? 'item' : 'items'}</Text>
      </View>
    </View>
  );
}

function PlaylistArtwork({
  compact = false,
  paths,
  selected = false,
}: {
  compact?: boolean;
  paths: string[];
  selected?: boolean;
}) {
  if (!paths.length) {
    return (
      <View style={[
        styles.playlistArtworkEmpty,
        compact && styles.playlistArtworkCompact,
        selected && styles.playlistArtworkSelected,
      ]}>
        <Image
          source={fallbackArtwork}
          style={[styles.playlistFallback, compact && styles.playlistFallbackCompact]}
        />
      </View>
    );
  }
  const tiles = paths.slice(0, 4);
  return (
    <View style={[
      styles.playlistArtworkGrid,
      compact && styles.playlistArtworkCompact,
      selected && styles.playlistArtworkSelected,
    ]}>
      {tiles.map((path, index) => (
        <MediaArtwork key={`${path}-${index}`} path={path} style={styles.playlistArtworkTile} />
      ))}
      {tiles.length < 4 ? Array.from({length: 4 - tiles.length}).map((_, index) => (
        <View key={`empty-${index}`} style={[styles.playlistArtworkTile, styles.playlistEmptyTile]}>
          <Image source={fallbackArtwork} style={styles.playlistTileFallback} />
        </View>
      )) : null}
    </View>
  );
}

function MediaRow({
  current,
  item,
  onLongPress,
  onMenu,
  onPress,
  playing,
  selected,
  selectionMode,
}: {
  current: boolean;
  item: MediaItem;
  onLongPress: () => void;
  onMenu: () => void;
  onPress: () => void;
  playing: boolean;
  selected: boolean;
  selectionMode: boolean;
}) {
  const downloading = item.availability === 'downloading';
  const playable = item.availability === 'ready' && Boolean(item.playbackLocator);
  const retryable = ['failed', 'missing', 'cancelled'].includes(item.availability);
  const progress = Math.max(0, Math.min(item.downloadProgress, 1));
  return (
    <View style={[styles.row, current && styles.currentRow]}>
      {current ? <View style={styles.currentMark} /> : null}
      <Pressable
        accessibilityHint={selectionMode ? 'Toggle selection' : statusCopy(item.availability, progress)}
        accessibilityLabel={item.title}
        accessibilityRole="button"
        onLongPress={onLongPress}
        onPress={onPress}
        style={({pressed}) => [styles.rowMain, pressed && styles.pressed]}>
        <View style={styles.artworkFrame}>
          <MediaArtwork path={item.thumbnailLocalPath} style={styles.artwork} />
          {selectionMode ? (
            <View style={[styles.selectionCheck, selected && styles.selectionCheckSelected]}>
              {selected ? <Text style={styles.selectionTick}>✓</Text> : null}
            </View>
          ) : null}
          {downloading && progress > 0 ? (
            <View style={styles.itemProgressTrack}>
              <View style={[styles.itemProgressFill, {width: `${progress * 100}%`}]} />
            </View>
          ) : null}
        </View>
        <View style={styles.copy}>
          <View style={styles.titleRow}>
            <Text numberOfLines={1} style={[styles.title, current && styles.currentTitle]}>{item.title}</Text>
            {playing ? <PlayingBars /> : null}
          </View>
          <Text numberOfLines={1} style={styles.subtitle}>
            {[item.artist || item.collectionName, sourceName(item.source)].filter(Boolean).join(' · ')}
          </Text>
          {item.availability !== 'ready' ? (
            <Text numberOfLines={1} style={[styles.status, retryable && styles.statusError]}>
              {statusCopy(item.availability, progress)}
            </Text>
          ) : null}
        </View>
      </Pressable>
      {!selectionMode ? (
        <View style={styles.rowActions}>
          {downloading ? (
            <View accessibilityLabel="Downloading" style={styles.primaryAction}>
              <ActivityIndicator color={colors.text} size="small" />
            </View>
          ) : playable || retryable ? (
            <Pressable
              accessibilityLabel={retryable ? 'Retry download' : 'Play'}
              onPress={onPress}
              style={({pressed}) => [styles.primaryAction, pressed && styles.pressed]}>
              {playable ? <PlayGlyph /> : null}
              {retryable ? <RetryGlyph /> : null}
            </Pressable>
          ) : null}
          <Pressable
            accessibilityLabel="More actions"
            hitSlop={4}
            onPress={onMenu}
            style={({pressed}) => [styles.menuButton, pressed && styles.pressed]}>
            <Text style={styles.menuDots}>•••</Text>
          </Pressable>
        </View>
      ) : null}
    </View>
  );
}

function PlayingBars() {
  return (
    <View accessibilityLabel="Playing" style={styles.playingBars}>
      <View style={[styles.playingBar, styles.playingBarShort]} />
      <View style={[styles.playingBar, styles.playingBarTall]} />
      <View style={[styles.playingBar, styles.playingBarMedium]} />
    </View>
  );
}

function SelectionBar({count, onAdd, onClose, onDelete}: {
  count: number;
  onAdd: () => void;
  onClose: () => void;
  onDelete: () => void;
}) {
  return (
    <View style={styles.selectionBar}>
      <Pressable accessibilityLabel="Clear selection" onPress={onClose} style={styles.selectionAction}>
        <Text style={styles.selectionClose}>×</Text>
      </Pressable>
      <Text style={styles.selectionCount}>{count} selected</Text>
      <Pressable accessibilityLabel="Add selected to playlist" onPress={onAdd} style={styles.selectionTextAction}>
        <Text style={styles.selectionText}>Add to playlist</Text>
      </Pressable>
      <Pressable accessibilityLabel="Delete selected" onPress={onDelete} style={styles.selectionAction}>
        <Text style={styles.deleteText}>Delete</Text>
      </Pressable>
    </View>
  );
}

function ItemActionSheet({activePlaylist, item, onAdd, onCancel, onClose, onPrimary, onRemove, onRemoveFromPlaylist}: {
  activePlaylist: LocalPlaylist | null;
  item: MediaItem | null;
  onAdd: () => void;
  onCancel: () => void;
  onClose: () => void;
  onPrimary: () => void;
  onRemove: () => void;
  onRemoveFromPlaylist: () => void;
}) {
  if (!item) return null;
  const playable = item.availability === 'ready' && Boolean(item.playbackLocator);
  const active = activeStates.has(item.availability);
  const retryable = ['failed', 'missing', 'cancelled'].includes(item.availability);
  return (
    <BottomSheet onClose={onClose} title={item.title}>
      {(playable || retryable) ? (
        <SheetAction
          label={playable ? 'Play' : 'Retry download'}
          onPress={onPrimary}
        />
      ) : null}
      {active ? (
        <SheetAction destructive label="Cancel download" onPress={onCancel} />
      ) : null}
      <SheetAction label="Add to playlist" onPress={onAdd} />
      {activePlaylist ? (
        <SheetAction label={`Remove from ${activePlaylist.name}`} onPress={onRemoveFromPlaylist} />
      ) : null}
      <SheetAction destructive label="Remove from library" onPress={onRemove} />
    </BottomSheet>
  );
}

function PlaylistPicker({busy, ids, name, onAdd, onChangeName, onClose, onCreate, playlists}: {
  busy: boolean;
  ids: string[] | null;
  name: string;
  onAdd: (playlist: LocalPlaylist) => void;
  onChangeName: (name: string) => void;
  onClose: () => void;
  onCreate: () => void;
  playlists: LocalPlaylist[];
}) {
  if (ids === null) return null;
  return (
    <BottomSheet onClose={onClose} title={ids.length ? 'Add to playlist' : 'Create playlist'}>
      {ids.length && playlists.length ? (
        <ScrollView style={styles.pickerList}>
          {playlists.map(playlist => (
            <Pressable
              disabled={busy}
              key={playlist.id}
              onPress={() => onAdd(playlist)}
              style={({pressed}) => [styles.pickerPlaylist, pressed && styles.pressed]}>
              <PlaylistArtwork compact paths={playlist.artworkPaths} />
              <View style={styles.pickerCopy}>
                <Text numberOfLines={1} style={styles.sheetActionText}>{playlist.name}</Text>
                <Text style={styles.playlistCount}>{playlist.itemCount} tracks</Text>
              </View>
            </Pressable>
          ))}
        </ScrollView>
      ) : null}
      <View style={styles.createRow}>
        <TextInput
          accessibilityLabel="Playlist name"
          onChangeText={onChangeName}
          onSubmitEditing={onCreate}
          placeholder="New playlist name"
          placeholderTextColor={colors.textSubtle}
          returnKeyType="done"
          selectionColor={colors.accent}
          style={styles.createInput}
          value={name}
        />
        <Pressable
          accessibilityLabel="Create playlist"
          disabled={!name.trim() || busy}
          onPress={onCreate}
          style={({pressed}) => [styles.createButton, (!name.trim() || busy) && styles.disabled, pressed && styles.pressed]}>
          {busy ? <ActivityIndicator color={colors.black} size="small" /> : <Text style={styles.createButtonText}>Create</Text>}
        </Pressable>
      </View>
    </BottomSheet>
  );
}

function PlaylistActionSheet({playlist, onClose, onDelete, onPin}: {
  playlist: LocalPlaylist | null;
  onClose: () => void;
  onDelete: () => void;
  onPin: () => void;
}) {
  if (!playlist) return null;
  return (
    <BottomSheet onClose={onClose} title={playlist.name}>
      <SheetAction label={playlist.pinned ? 'Unpin playlist' : 'Pin playlist'} onPress={onPin} />
      <SheetAction destructive label="Delete playlist" onPress={onDelete} />
    </BottomSheet>
  );
}

function FilterSheet({
  current,
  onClose,
  onSelect,
  visible,
}: {
  current: SourceFilter;
  onClose: () => void;
  onSelect: (filter: SourceFilter) => void;
  visible: boolean;
}) {
  if (!visible) return null;
  return (
    <BottomSheet onClose={onClose} title="Show media from">
      {filters.map(value => (
        <Pressable
          accessibilityRole="button"
          accessibilityState={{selected: current === value.id}}
          key={value.id}
          onPress={() => onSelect(value.id)}
          style={({pressed}) => [styles.filterSheetAction, pressed && styles.pressed]}>
          <Text style={[
            styles.sheetActionText,
            current === value.id && styles.filterSheetTextActive,
          ]}>
            {value.label}
          </Text>
          {current === value.id ? <Text style={styles.filterCheck}>✓</Text> : null}
        </Pressable>
      ))}
    </BottomSheet>
  );
}

function BottomSheet({children, onClose, title}: {children: React.ReactNode; onClose: () => void; title: string}) {
  return (
    <Modal animationType="fade" onRequestClose={onClose} transparent visible>
      <View style={styles.modalRoot}>
        <Pressable accessibilityLabel="Close" onPress={onClose} style={styles.modalScrim} />
        <View style={styles.sheet}>
          <View style={styles.sheetHandle} />
          <Text numberOfLines={1} style={styles.sheetTitle}>{title}</Text>
          {children}
        </View>
      </View>
    </Modal>
  );
}

function SheetAction({destructive = false, label, onPress}: {destructive?: boolean; label: string; onPress: () => void}) {
  return (
    <Pressable onPress={onPress} style={({pressed}) => [styles.sheetAction, pressed && styles.pressed]}>
      <Text style={[styles.sheetActionText, destructive && styles.deleteText]}>{label}</Text>
    </Pressable>
  );
}

function PlayGlyph() {
  return <View style={styles.playGlyph} />;
}

function RetryGlyph() {
  return <Text style={styles.retryGlyph}>↻</Text>;
}

function EmptyLibrary({filtered}: {filtered: boolean}) {
  return (
    <View style={styles.empty}>
      <Image source={fallbackArtwork} style={styles.emptyIcon} />
      <Text style={styles.emptyTitle}>{filtered ? 'Nothing here' : 'Your offline library is empty'}</Text>
      <Text style={styles.emptyCopy}>
        {filtered ? 'Try another source, playlist, or search.' : 'Choose a source above to add media, or import it from Gallery.'}
      </Text>
    </View>
  );
}

function sourceName(source: MediaItem['source']) {
  if (source === 'youtube-music') return 'YouTube Music';
  if (source === 'youtube') return 'YouTube';
  if (source === 'gallery') return 'Gallery';
  return 'Web import';
}

function statusCopy(value: MediaAvailability, progress = 0) {
  switch (value) {
    case 'waiting_for_resolver':
    case 'queued': return 'Queued for download';
    case 'downloading': return progress > 0
      ? `Downloading · ${Math.round(progress * 100)}%`
      : 'Starting download…';
    case 'failed': return 'Download failed';
    case 'missing': return 'Source file is missing';
    case 'cancelled': return 'Download cancelled';
    case 'ready': return 'Ready offline';
  }
}

function downloadSortRank(value: MediaAvailability) {
  if (value === 'downloading') return 2;
  if (value === 'queued' || value === 'waiting_for_resolver') return 1;
  return 0;
}

const styles = StyleSheet.create({
  container: {backgroundColor: colors.canvas, flex: 1},
  centered: {alignItems: 'center', backgroundColor: colors.canvas, flex: 1, justifyContent: 'center'},
  list: {paddingBottom: 92},
  emptyList: {flexGrow: 1, paddingBottom: 92},
  playlistTitleRow: {alignItems: 'center', flexDirection: 'row', marginTop: spacing.md, paddingHorizontal: spacing.md},
  sectionTitle: {color: colors.text, fontSize: typography.title, fontWeight: '800', letterSpacing: -0.2},
  activePlaylistName: {color: colors.accent, flex: 1, fontSize: typography.caption, marginLeft: spacing.sm, textAlign: 'right'},
  playlistList: {paddingHorizontal: spacing.md, paddingVertical: spacing.sm},
  playlistCard: {marginRight: spacing.md, paddingBottom: spacing.xs, position: 'relative', width: 104},
  newPlaylistArtwork: {
    alignItems: 'center',
    backgroundColor: colors.surfaceRaised,
    borderColor: colors.border,
    borderRadius: radii.playlist,
    borderWidth: StyleSheet.hairlineWidth,
    height: 104,
    justifyContent: 'center',
    width: 104,
  },
  plus: {color: colors.text, fontSize: 26, fontWeight: '300'},
  playlistArtworkGrid: {backgroundColor: colors.surfaceRaised, borderRadius: radii.playlist, flexDirection: 'row', flexWrap: 'wrap', height: 104, overflow: 'hidden', width: 104},
  playlistArtworkTile: {backgroundColor: colors.surfaceRaised, height: '50%', width: '50%'},
  playlistEmptyTile: {alignItems: 'center', justifyContent: 'center'},
  playlistTileFallback: {height: 14, opacity: 0.42, width: 14},
  playlistArtworkEmpty: {alignItems: 'center', backgroundColor: colors.surfaceRaised, borderRadius: radii.playlist, height: 104, justifyContent: 'center', overflow: 'hidden', width: 104},
  playlistArtworkCompact: {borderRadius: radii.artwork, height: 46, width: 46},
  playlistArtworkSelected: {borderColor: colors.accent, borderWidth: 2},
  playlistFallback: {height: 32, opacity: 0.62, width: 32},
  playlistFallbackCompact: {height: 17, width: 17},
  playlistName: {color: colors.text, fontSize: typography.caption, fontWeight: '700', marginTop: spacing.sm},
  playlistNameActive: {color: colors.accent},
  playlistCount: {color: colors.textSubtle, fontSize: typography.utility, marginTop: spacing.xxs},
  libraryControlRow: {alignItems: 'center', flexDirection: 'row', minHeight: 44, paddingHorizontal: spacing.md},
  filterButton: {alignItems: 'center', flexDirection: 'row', minHeight: 44, paddingRight: spacing.md},
  filterButtonText: {color: colors.text, fontSize: typography.caption, fontWeight: '700'},
  filterChevron: {color: colors.textMuted, fontSize: typography.body, marginLeft: spacing.xs, marginTop: -2},
  itemCount: {color: colors.textSubtle, flex: 1, fontSize: typography.utility, textAlign: 'right'},
  row: {alignItems: 'center', flexDirection: 'row', minHeight: 76, paddingLeft: spacing.md, position: 'relative'},
  currentRow: {backgroundColor: colors.surface},
  currentMark: {backgroundColor: colors.accent, bottom: spacing.sm, left: 0, position: 'absolute', top: spacing.sm, width: 2},
  rowMain: {alignItems: 'center', flex: 1, flexDirection: 'row', minWidth: 0, paddingVertical: spacing.sm},
  artworkFrame: {borderRadius: radii.artwork, height: 56, overflow: 'hidden', position: 'relative', width: 56},
  artwork: {borderRadius: radii.artwork, height: 56, width: 56},
  itemProgressTrack: {backgroundColor: colors.border, bottom: 0, height: 2, left: 0, position: 'absolute', right: 0},
  itemProgressFill: {backgroundColor: colors.accent, height: 2},
  selectionCheck: {alignItems: 'center', backgroundColor: colors.scrimStrong, borderColor: colors.textMuted, borderRadius: radii.round, borderWidth: 1, height: 22, justifyContent: 'center', position: 'absolute', right: 5, top: 5, width: 22},
  selectionCheckSelected: {backgroundColor: colors.accent, borderColor: colors.accent},
  selectionTick: {color: colors.white, fontSize: 13, fontWeight: '900'},
  copy: {flex: 1, marginLeft: spacing.md, minWidth: 0},
  titleRow: {alignItems: 'center', flexDirection: 'row'},
  title: {color: colors.text, flexShrink: 1, fontSize: typography.body, fontWeight: '700'},
  currentTitle: {color: colors.accent},
  subtitle: {color: colors.textMuted, fontSize: typography.caption, marginTop: spacing.xs},
  status: {color: colors.textSubtle, fontSize: typography.utility, marginTop: spacing.xs},
  statusError: {color: colors.textMuted},
  playingBars: {alignItems: 'flex-end', flexDirection: 'row', height: 14, marginLeft: spacing.sm},
  playingBar: {backgroundColor: colors.accent, marginHorizontal: 1, width: 2},
  playingBarShort: {height: 5},
  playingBarMedium: {height: 8},
  playingBarTall: {height: 12},
  rowActions: {alignItems: 'center', flexDirection: 'row', paddingRight: spacing.xs},
  primaryAction: {alignItems: 'center', height: 44, justifyContent: 'center', width: 40},
  menuButton: {alignItems: 'center', height: 44, justifyContent: 'center', width: 34},
  menuDots: {color: colors.textMuted, fontSize: 12, letterSpacing: 1, transform: [{rotate: '90deg'}]},
  playGlyph: {borderBottomColor: colors.transparent, borderBottomWidth: 7, borderLeftColor: colors.text, borderLeftWidth: 11, borderTopColor: colors.transparent, borderTopWidth: 7, height: 0, marginLeft: 2, width: 0},
  retryGlyph: {color: colors.text, fontSize: 24, fontWeight: '300'},
  selectionBar: {alignItems: 'center', backgroundColor: colors.surfaceOverlay, borderColor: colors.border, borderRadius: radii.lg, borderWidth: StyleSheet.hairlineWidth, bottom: spacing.md, flexDirection: 'row', left: spacing.md, minHeight: 56, paddingHorizontal: spacing.sm, position: 'absolute', right: spacing.md},
  selectionAction: {alignItems: 'center', minHeight: 44, justifyContent: 'center', paddingHorizontal: spacing.sm},
  selectionClose: {color: colors.text, fontSize: 26, fontWeight: '300'},
  selectionCount: {color: colors.text, flex: 1, fontSize: typography.caption, fontWeight: '700'},
  selectionTextAction: {justifyContent: 'center', minHeight: 44, paddingHorizontal: spacing.sm},
  selectionText: {color: colors.text, fontSize: typography.caption, fontWeight: '700'},
  deleteText: {color: colors.accent, fontSize: typography.caption, fontWeight: '700'},
  modalRoot: {flex: 1, justifyContent: 'flex-end'},
  modalScrim: {backgroundColor: colors.scrim, bottom: 0, left: 0, position: 'absolute', right: 0, top: 0},
  sheet: {backgroundColor: colors.surfaceOverlay, borderTopLeftRadius: radii.lg, borderTopRightRadius: radii.lg, maxHeight: '78%', paddingBottom: spacing.xl, paddingHorizontal: spacing.lg},
  sheetHandle: {alignSelf: 'center', backgroundColor: colors.textSubtle, borderRadius: radii.round, height: 3, marginVertical: spacing.sm, width: 34},
  sheetTitle: {color: colors.text, fontSize: typography.title, fontWeight: '800', marginBottom: spacing.sm, marginTop: spacing.xs},
  sheetAction: {justifyContent: 'center', minHeight: 50},
  sheetActionText: {color: colors.text, fontSize: typography.body, fontWeight: '700'},
  filterSheetAction: {alignItems: 'center', flexDirection: 'row', minHeight: 50},
  filterSheetTextActive: {color: colors.accent},
  filterCheck: {color: colors.accent, fontSize: typography.body, fontWeight: '800', marginLeft: 'auto'},
  pickerList: {maxHeight: 260},
  pickerPlaylist: {alignItems: 'center', flexDirection: 'row', minHeight: 70},
  pickerCopy: {flex: 1, marginLeft: spacing.md},
  createRow: {alignItems: 'center', borderTopColor: colors.border, borderTopWidth: StyleSheet.hairlineWidth, flexDirection: 'row', marginTop: spacing.sm, paddingTop: spacing.md},
  createInput: {backgroundColor: colors.surfaceRaised, borderColor: colors.border, borderRadius: radii.md, borderWidth: StyleSheet.hairlineWidth, color: colors.text, flex: 1, fontSize: typography.body, height: 46, paddingHorizontal: spacing.md},
  createButton: {alignItems: 'center', backgroundColor: colors.text, borderRadius: radii.md, height: 46, justifyContent: 'center', marginLeft: spacing.sm, paddingHorizontal: spacing.lg},
  createButtonText: {color: colors.black, fontSize: typography.caption, fontWeight: '800'},
  disabled: {opacity: 0.34},
  empty: {alignItems: 'center', flex: 1, justifyContent: 'center', minHeight: 260, paddingHorizontal: spacing.xl},
  emptyIcon: {height: 34, opacity: 0.68, width: 34},
  emptyTitle: {color: colors.text, fontSize: typography.title, fontWeight: '800', marginTop: spacing.lg},
  emptyCopy: {color: colors.textMuted, fontSize: typography.caption, lineHeight: 17, marginTop: spacing.sm, textAlign: 'center'},
  pressed: {opacity: 0.58},
});

export default LibrarySurface;
