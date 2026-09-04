import React, {useCallback, useEffect, useState} from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Image,
  Pressable,
  StyleSheet,
  Text,
  ToastAndroid,
  View,
} from 'react-native';
import {
  ChevronRight,
  Download,
  Film,
  MoreHorizontal,
  Plus,
} from 'lucide-react-native';

import {
  createReelSnapshot,
  deleteReelSnapshot,
  getDoomscrollStats,
  listReelSnapshots,
  onDoomscrollChanged,
} from '../../native/DoomscrollEngine';
import {getUiPreference} from '../../native/MediaEngine';
import {colors, layout, radii, spacing, typography} from '../../theme';
import {
  DEFAULT_REEL_QUALITY,
  DOOMSCROLL_QUALITY_PREFERENCE,
  parseReelQuality,
} from './preferences';
import type {DoomscrollStats, ReelQualityPolicy, ReelSnapshot} from './types';
import {emptyDoomscrollStats} from './types';

type Props = {
  onCapture: (snapshotId: string) => void;
  onOpen: (snapshot: ReelSnapshot) => void;
};

function SnapshotLibrarySurface({onCapture, onOpen}: Props) {
  const [snapshots, setSnapshots] = useState<ReelSnapshot[]>([]);
  const [stats, setStats] = useState<DoomscrollStats>(emptyDoomscrollStats);
  const [qualityPolicy, setQualityPolicy] =
    useState<ReelQualityPolicy>(DEFAULT_REEL_QUALITY);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [nextSnapshots, nextStats, storedQuality] = await Promise.all([
        listReelSnapshots(),
        getDoomscrollStats(),
        getUiPreference(DOOMSCROLL_QUALITY_PREFERENCE),
      ]);
      setSnapshots(nextSnapshots);
      setStats(nextStats);
      setQualityPolicy(parseReelQuality(storedQuality));
      setError(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Snapshots could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh().catch(() => undefined);
    const changed = onDoomscrollChanged(refresh);
    return () => changed?.remove();
  }, [refresh]);

  const createSnapshot = useCallback(async () => {
    if (creating) return;
    setCreating(true);
    try {
      const id = await createReelSnapshot(snapshotName(new Date()), qualityPolicy);
      onCapture(id);
    } catch (reason) {
      ToastAndroid.show(
        reason instanceof Error ? reason.message : 'Could not create a snapshot.',
        ToastAndroid.LONG,
      );
    } finally {
      setCreating(false);
    }
  }, [creating, onCapture, qualityPolicy]);

  const confirmDelete = useCallback((snapshot: ReelSnapshot) => {
    const reclaim = snapshot.reclaimableBytes > 0
      ? `${formatBytes(snapshot.reclaimableBytes)} will be reclaimed. `
      : '';
    Alert.alert(
      'Delete this snapshot?',
      `${reclaim}Media also used by another snapshot will be kept.`,
      [
        {text: 'Cancel', style: 'cancel'},
        {
          text: 'Delete snapshot',
          style: 'destructive',
          onPress: () => {
            deleteReelSnapshot(snapshot.id)
              .then(result => {
                setSnapshots(current => current.filter(item => item.id !== snapshot.id));
                ToastAndroid.show(
                  result.reclaimedBytes > 0
                    ? `${formatBytes(result.reclaimedBytes)} reclaimed`
                    : 'Snapshot deleted',
                  ToastAndroid.SHORT,
                );
                refresh().catch(() => undefined);
              })
              .catch(() => ToastAndroid.show('Could not delete snapshot', ToastAndroid.SHORT));
          },
        },
      ],
    );
  }, [refresh]);

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <View>
          <Text style={styles.headerTitle}>Snapshots</Text>
          {stats.downloadedBytes > 0 ? (
            <Text style={styles.headerMeta}>{formatBytes(stats.downloadedBytes)} offline</Text>
          ) : null}
        </View>
        <Pressable
          accessibilityLabel="Create a Reel snapshot"
          accessibilityRole="button"
          disabled={creating}
          hitSlop={4}
          onPress={() => { createSnapshot().catch(() => undefined); }}
          style={({pressed}) => [
            styles.addButton,
            creating && styles.disabled,
            pressed && styles.pressed,
          ]}>
          {creating ? (
            <ActivityIndicator color={colors.text} size="small" />
          ) : (
            <Plus color={colors.text} size={23} strokeWidth={1.8} />
          )}
        </Pressable>
      </View>

      <FlatList
        ListEmptyComponent={
          loading ? (
            <View style={styles.centerState}>
              <ActivityIndicator color={colors.textMuted} />
            </View>
          ) : (
            <View style={styles.emptyState}>
              <Film color={colors.textSubtle} size={36} strokeWidth={1.35} />
              <Text style={styles.emptyTitle}>No Reel snapshots</Text>
              <Text style={styles.emptyCopy}>
                Create one while connected, then keep scrolling when the network disappears.
              </Text>
              {error ? <Text style={styles.errorText}>{error}</Text> : null}
              <Pressable
                accessibilityLabel="Create the first Reel snapshot"
                accessibilityRole="button"
                disabled={creating}
                onPress={() => { createSnapshot().catch(() => undefined); }}
                style={({pressed}) => [styles.emptyAction, pressed && styles.pressed]}>
                <Plus color={colors.black} size={17} strokeWidth={2} />
                <Text style={styles.emptyActionText}>New snapshot</Text>
              </Pressable>
            </View>
          )
        }
        contentContainerStyle={[
          styles.listContent,
          snapshots.length === 0 && styles.emptyListContent,
        ]}
        data={snapshots}
        keyExtractor={item => item.id}
        onRefresh={() => { refresh().catch(() => undefined); }}
        refreshing={loading && snapshots.length > 0}
        renderItem={({item}) => (
          <SnapshotRow
            onDelete={() => confirmDelete(item)}
            onOpen={() => onOpen(item)}
            snapshot={item}
          />
        )}
        showsVerticalScrollIndicator={false}
      />
    </View>
  );
}

function SnapshotRow({
  onDelete,
  onOpen,
  snapshot,
}: {
  onDelete: () => void;
  onOpen: () => void;
  snapshot: ReelSnapshot;
}) {
  const downloads = snapshot.queuedCount + snapshot.downloadingCount;
  const problems = snapshot.failedCount + snapshot.lowStorageCount;
  const remaining = Math.max(snapshot.capturedCount - snapshot.watchedCount, 0);
  const progress = snapshot.capturedCount > 0
    ? snapshot.watchedCount / snapshot.capturedCount
    : 0;

  return (
    <Pressable
      accessibilityLabel={`${formatSnapshotTitle(snapshot.createdAt)}, ${snapshot.capturedCount} Reels`}
      accessibilityRole="button"
      accessibilityState={{disabled: snapshot.capturedCount === 0}}
      onPress={() => {
        if (snapshot.capturedCount > 0) onOpen();
      }}
      style={({pressed}) => [styles.snapshotRow, pressed && styles.rowPressed]}>
      <SnapshotPreview paths={snapshot.previewCoverPaths} />
      <View style={styles.snapshotCopy}>
        <View style={styles.snapshotTitleRow}>
          <Text numberOfLines={1} style={styles.snapshotTitle}>
            {formatSnapshotTitle(snapshot.createdAt)}
          </Text>
          <Pressable
            accessibilityLabel="Snapshot options"
            accessibilityRole="button"
            hitSlop={8}
            onPress={event => {
              event.stopPropagation();
              Alert.alert(
                formatSnapshotTitle(snapshot.createdAt),
                `${snapshot.capturedCount} Reels · ${formatBytes(snapshot.logicalBytes)} stored`,
                [
                  {text: 'Cancel', style: 'cancel'},
                  {text: 'Delete snapshot', style: 'destructive', onPress: onDelete},
                ],
              );
            }}
            style={({pressed}) => [styles.moreButton, pressed && styles.pressed]}>
            <MoreHorizontal color={colors.textMuted} size={20} strokeWidth={1.8} />
          </Pressable>
        </View>
        <Text style={styles.snapshotMeta}>
          {snapshot.capturedCount} Reels · {snapshot.readyCount} ready · {formatBytes(snapshot.logicalBytes)}
        </Text>

        <View style={styles.progressTrack}>
          <View style={[styles.progressFill, {width: `${progress * 100}%`}]} />
        </View>

        <View style={styles.snapshotFooter}>
          <View style={styles.statusWrap}>
            {downloads > 0 ? <Download color={colors.textMuted} size={13} strokeWidth={1.8} /> : null}
            <Text
              numberOfLines={1}
              style={[styles.statusText, problems > 0 && styles.warningText]}>
              {downloads > 0
                ? `Saving ${downloads}`
                : problems > 0
                  ? `${problems} need attention`
                  : snapshot.capturedCount === 0
                    ? 'Capture not started'
                    : remaining > 0
                      ? `${remaining} unwatched`
                      : 'Watched'}
            </Text>
          </View>
          {snapshot.capturedCount > 0 ? (
            <ChevronRight color={colors.textSubtle} size={17} strokeWidth={1.8} />
          ) : null}
        </View>
      </View>
    </Pressable>
  );
}

function SnapshotPreview({paths}: {paths: string[]}) {
  return (
    <View style={styles.previewStrip}>
      {[0, 1, 2].map(index => {
        const path = paths[index];
        return path ? (
          <Image
            accessibilityIgnoresInvertColors
            key={path}
            resizeMode="cover"
            source={{uri: localFileUri(path)}}
            style={styles.preview}
          />
        ) : (
          <View key={index} style={[styles.preview, styles.previewEmpty]} />
        );
      })}
    </View>
  );
}

function snapshotName(date: Date) {
  return `Snapshot · ${date.toLocaleDateString(undefined, {day: 'numeric', month: 'short'})} ${date.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'})}`;
}

function formatSnapshotTitle(value: number) {
  const date = new Date(value);
  const now = new Date();
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  const sameDay = (left: Date, right: Date) =>
    left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate();
  const day = sameDay(date, now)
    ? 'Today'
    : sameDay(date, yesterday)
      ? 'Yesterday'
      : date.toLocaleDateString(undefined, {day: 'numeric', month: 'short'});
  return `${day}, ${date.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'})}`;
}

function localFileUri(path: string) {
  return path.startsWith('file://') ? path : `file://${path}`;
}

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  const scaled = value / 1024 ** index;
  return `${scaled >= 10 || index === 0 ? scaled.toFixed(0) : scaled.toFixed(1)} ${units[index]}`;
}

const styles = StyleSheet.create({
  addButton: {
    alignItems: 'center',
    height: layout.minTouchTarget,
    justifyContent: 'center',
    marginRight: -spacing.sm,
    width: layout.minTouchTarget,
  },
  centerState: {alignItems: 'center', flex: 1, justifyContent: 'center'},
  container: {backgroundColor: colors.canvas, flex: 1},
  disabled: {opacity: 0.45},
  emptyAction: {
    alignItems: 'center',
    backgroundColor: colors.text,
    borderRadius: radii.sm,
    flexDirection: 'row',
    marginTop: spacing.xl,
    minHeight: layout.minTouchTarget,
    paddingHorizontal: spacing.lg,
  },
  emptyActionText: {color: colors.black, fontSize: typography.body, fontWeight: '700', marginLeft: spacing.sm},
  emptyCopy: {color: colors.textMuted, fontSize: typography.body, lineHeight: 19, marginTop: spacing.sm, maxWidth: 310, textAlign: 'center'},
  emptyListContent: {flexGrow: 1},
  emptyState: {alignItems: 'center', flex: 1, justifyContent: 'center', padding: spacing.xxl},
  emptyTitle: {color: colors.text, fontSize: 18, fontWeight: '700', marginTop: spacing.lg},
  errorText: {color: colors.warning, fontSize: typography.caption, marginTop: spacing.md, textAlign: 'center'},
  header: {
    alignItems: 'center',
    backgroundColor: colors.surface,
    borderBottomColor: colors.border,
    borderBottomWidth: StyleSheet.hairlineWidth,
    flexDirection: 'row',
    height: layout.sourceBar,
    justifyContent: 'space-between',
    paddingHorizontal: spacing.lg,
  },
  headerMeta: {color: colors.textSubtle, fontSize: 9, marginTop: 1},
  headerTitle: {color: colors.text, fontSize: typography.title, fontWeight: '700'},
  listContent: {paddingBottom: spacing.xxl},
  moreButton: {alignItems: 'center', height: 32, justifyContent: 'center', marginRight: -spacing.sm, marginTop: -spacing.sm, width: 36},
  pressed: {opacity: 0.58},
  preview: {flex: 1, height: 92},
  previewEmpty: {backgroundColor: colors.surfaceRaised},
  previewStrip: {
    backgroundColor: colors.surfaceRaised,
    borderRadius: radii.artwork,
    flexDirection: 'row',
    height: 92,
    overflow: 'hidden',
    width: 72,
  },
  progressFill: {backgroundColor: colors.textMuted, height: 2},
  progressTrack: {backgroundColor: colors.surfaceOverlay, height: 2, marginTop: spacing.md, overflow: 'hidden'},
  rowPressed: {backgroundColor: colors.surfaceRaised},
  snapshotCopy: {flex: 1, justifyContent: 'center', minWidth: 0, paddingLeft: spacing.md},
  snapshotFooter: {alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between', marginTop: spacing.sm},
  snapshotMeta: {color: colors.textMuted, fontSize: typography.utility, marginTop: 3},
  snapshotRow: {
    borderBottomColor: colors.border,
    borderBottomWidth: StyleSheet.hairlineWidth,
    flexDirection: 'row',
    minHeight: 116,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
  },
  snapshotTitle: {color: colors.text, flex: 1, fontSize: typography.body, fontWeight: '700'},
  snapshotTitleRow: {alignItems: 'center', flexDirection: 'row'},
  statusText: {color: colors.textSubtle, fontSize: typography.utility, marginLeft: spacing.xs},
  statusWrap: {alignItems: 'center', flex: 1, flexDirection: 'row', minWidth: 0},
  warningText: {color: colors.warning, marginLeft: 0},
});

export default SnapshotLibrarySurface;
