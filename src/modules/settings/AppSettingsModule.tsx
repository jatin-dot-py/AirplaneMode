import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  ToastAndroid,
  View,
} from 'react-native';
import {
  Check,
  ChevronRight,
  Database,
  Gauge,
  HardDrive,
  Info,
  RefreshCw,
  ShieldCheck,
  Trash2,
  Video,
  Wifi,
  type LucideIcon,
} from 'lucide-react-native';
import packageMetadata from '../../../package.json';

import {
  clearInstagramWebCache,
  clearInstagramWebsiteData,
  clearOfflineReels,
  getDoomscrollStorageBreakdown,
  onDoomscrollChanged,
} from '../../native/DoomscrollEngine';
import {
  clearMediaLibrary,
  getStorageStats,
  getUiPreference,
  onLibraryChanged,
  setUiPreference,
  type StorageStats,
} from '../../native/MediaEngine';
import {colors, layout, radii, spacing, typography} from '../../theme';
import {
  DEFAULT_REEL_QUALITY,
  DEFAULT_REEL_SPEED,
  DOOMSCROLL_QUALITY_PREFERENCE,
  DOOMSCROLL_SPEED_PREFERENCE,
  REEL_PLAYBACK_SPEEDS,
  REEL_QUALITY_OPTIONS,
  parseReelQuality,
  parseReelSpeed,
} from '../doomscroller/preferences';
import type {
  DoomscrollStorageBreakdown,
  ReelQualityPolicy,
} from '../doomscroller/types';

type ClearAction =
  | 'all'
  | 'media'
  | 'reels'
  | 'website-cache'
  | 'website-data';

function AppSettingsModule() {
  const [mediaStorage, setMediaStorage] = useState<StorageStats | null>(null);
  const [doomscrollStorage, setDoomscrollStorage] =
    useState<DoomscrollStorageBreakdown | null>(null);
  const [quality, setQuality] = useState<ReelQualityPolicy>(DEFAULT_REEL_QUALITY);
  const [speed, setSpeed] = useState(DEFAULT_REEL_SPEED);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<ClearAction | null>(null);
  const [storageError, setStorageError] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [nextMediaStorage, nextDoomscrollStorage] = await Promise.all([
        getStorageStats(),
        getDoomscrollStorageBreakdown(),
      ]);
      setMediaStorage(nextMediaStorage);
      setDoomscrollStorage(nextDoomscrollStorage);
      setStorageError(false);
    } catch {
      setStorageError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    Promise.all([
      getUiPreference(DOOMSCROLL_QUALITY_PREFERENCE),
      getUiPreference(DOOMSCROLL_SPEED_PREFERENCE),
    ]).then(([storedQuality, storedSpeed]) => {
      setQuality(parseReelQuality(storedQuality));
      setSpeed(parseReelSpeed(storedSpeed));
    }).catch(() => undefined);
    refresh().catch(() => undefined);
    const mediaChanged = onLibraryChanged(refresh);
    const doomscrollChanged = onDoomscrollChanged(refresh);
    return () => {
      mediaChanged?.remove();
      doomscrollChanged?.remove();
    };
  }, [refresh]);

  const chooseQuality = useCallback((value: ReelQualityPolicy) => {
    setQuality(value);
    setUiPreference(DOOMSCROLL_QUALITY_PREFERENCE, value).catch(() => undefined);
  }, []);

  const chooseSpeed = useCallback((value: number) => {
    setSpeed(value);
    setUiPreference(DOOMSCROLL_SPEED_PREFERENCE, String(value)).catch(() => undefined);
  }, []);

  const clear = useCallback(async (action: ClearAction) => {
    if (busy) return;
    setBusy(action);
    try {
      if (action === 'media') await clearMediaLibrary();
      if (action === 'reels') await clearOfflineReels();
      if (action === 'website-cache') await clearInstagramWebCache();
      if (action === 'website-data') await clearInstagramWebsiteData();
      if (action === 'all') {
        await clearMediaLibrary();
        await clearOfflineReels();
        await clearInstagramWebsiteData();
        await Promise.all([
          setUiPreference(DOOMSCROLL_QUALITY_PREFERENCE, DEFAULT_REEL_QUALITY),
          setUiPreference(DOOMSCROLL_SPEED_PREFERENCE, String(DEFAULT_REEL_SPEED)),
        ]);
        setQuality(DEFAULT_REEL_QUALITY);
        setSpeed(DEFAULT_REEL_SPEED);
      }
      await refresh();
      ToastAndroid.show(clearSuccessMessage(action), ToastAndroid.SHORT);
    } catch (reason) {
      ToastAndroid.show(
        reason instanceof Error ? reason.message : 'The data could not be cleared.',
        ToastAndroid.LONG,
      );
    } finally {
      setBusy(null);
    }
  }, [busy, refresh]);

  const confirmClear = useCallback((action: ClearAction) => {
    const content = clearConfirmation(action);
    Alert.alert(content.title, content.message, [
      {text: 'Cancel', style: 'cancel'},
      {
        text: content.confirm,
        style: 'destructive',
        onPress: () => { clear(action).catch(() => undefined); },
      },
    ]);
  }, [clear]);

  const showPrivacy = useCallback(() => {
    Alert.alert(
      'Privacy & third-party services',
      'AirplaneMode has no developer-operated account, analytics service, or media server. ' +
        'Site sign-ins and cookies remain in Android WebView storage. Sanitized metadata and ' +
        'downloaded files remain in app-private storage on this device until you delete them.\n\n' +
        'Instagram, YouTube, and other connected websites still receive normal web requests ' +
        'and apply their own terms and privacy policies.',
      [{text: 'Done'}],
    );
  }, []);

  const sizes = useMemo(() => {
    const mediaFiles = (mediaStorage?.appMediaBytes ?? 0) +
      (mediaStorage?.artworkBytes ?? 0);
    const database = (mediaStorage?.databaseBytes ?? 0) +
      (doomscrollStorage?.databaseBytes ?? 0);
    const reels = doomscrollStorage?.mediaBytes ?? 0;
    const website = doomscrollStorage?.websiteDataBytes ?? 0;
    return {
      database,
      mediaFiles,
      reels,
      total: mediaFiles + database + reels + website,
      website,
    };
  }, [doomscrollStorage, mediaStorage]);

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>Settings</Text>
        {loading ? <ActivityIndicator color={colors.textMuted} size="small" /> : null}
      </View>

      <ScrollView
        contentContainerStyle={styles.content}
        showsVerticalScrollIndicator={false}>
        <SectionHeader
          description="Used for new Reel snapshots. Existing snapshots keep their original files."
          title="Reel downloads"
        />
        <View style={styles.section}>
          {REEL_QUALITY_OPTIONS.map(option => (
            <ChoiceRow
              detail={option.detail}
              key={option.value}
              label={option.label}
              onPress={() => chooseQuality(option.value)}
              selected={quality === option.value}
            />
          ))}
        </View>

        <SectionHeader
          description="The default applies to every offline Reel and can also be changed in the viewer."
          title="Playback speed"
        />
        <View style={styles.speedRow}>
          <Gauge color={colors.textMuted} size={19} strokeWidth={1.8} />
          <View style={styles.speedChoices}>
            {REEL_PLAYBACK_SPEEDS.map(value => (
              <Pressable
                accessibilityLabel={`${value} times playback speed`}
                accessibilityRole="radio"
                accessibilityState={{checked: speed === value}}
                key={value}
                onPress={() => chooseSpeed(value)}
                style={({pressed}) => [
                  styles.speedChoice,
                  speed === value && styles.speedChoiceSelected,
                  pressed && styles.pressed,
                ]}>
                <Text style={[
                  styles.speedChoiceText,
                  speed === value && styles.speedChoiceTextSelected,
                ]}>
                  {value}×
                </Text>
              </Pressable>
            ))}
          </View>
        </View>

        <SectionHeader
          accessory={formatBytes(sizes.total)}
          description="Only files managed by AirplaneMode are counted. Linked Gallery files stay on the device."
          title="Storage"
        />
        <View style={styles.section}>
          <InfoRow icon={Video} label="Media Player files" value={formatBytes(sizes.mediaFiles)} />
          <InfoRow icon={HardDrive} label="Reel snapshots" value={formatBytes(sizes.reels)} />
          <InfoRow icon={Database} label="Databases" value={formatBytes(sizes.database)} />
          <InfoRow icon={Wifi} label="Website data" value={formatBytes(sizes.website)} />
        </View>
        {storageError ? (
          <Pressable
            accessibilityLabel="Retry storage calculation"
            onPress={() => { refresh().catch(() => undefined); }}
            style={({pressed}) => [styles.refresh, pressed && styles.pressed]}>
            <RefreshCw color={colors.accent} size={15} />
            <Text style={styles.refreshText}>Refresh storage details</Text>
          </Pressable>
        ) : null}

        <SectionHeader
          description="Each action is confirmed first. Clearing website data signs you out of connected sites."
          title="Clear data"
        />
        <View style={styles.section}>
          <ActionRow
            busy={busy === 'media'}
            detail="Downloads, artwork, playlists, and Media Player database records"
            icon={Video}
            label="Clear Media Player"
            onPress={() => confirmClear('media')}
          />
          <ActionRow
            busy={busy === 'reels'}
            detail="Every snapshot, offline Reel, cover, avatar, and Reel database record"
            icon={HardDrive}
            label="Clear Reel snapshots"
            onPress={() => confirmClear('reels')}
          />
          <ActionRow
            busy={busy === 'website-cache'}
            detail="Temporary website files; sign-ins and site storage are kept"
            icon={RefreshCw}
            label="Clear website cache"
            onPress={() => confirmClear('website-cache')}
          />
          <ActionRow
            busy={busy === 'website-data'}
            detail="Cookies, website storage, browsing cache, and connected-site sign-ins"
            icon={ShieldCheck}
            label="Reset website data"
            onPress={() => confirmClear('website-data')}
          />
          <ActionRow
            busy={busy === 'all'}
            destructive
            detail="All app-managed media, databases, snapshots, website data, and these defaults"
            icon={Trash2}
            label="Clear everything"
            onPress={() => confirmClear('all')}
          />
        </View>

        <SectionHeader
          description="Local-first by design. Connected websites remain governed by their own policies."
          title="About"
        />
        <View style={styles.section}>
          <ActionRow
            busy={false}
            detail="What stays on this device and what connected websites can receive"
            icon={ShieldCheck}
            label="Privacy & data"
            onPress={showPrivacy}
          />
          <InfoRow icon={Info} label="Version" value={packageMetadata.version} />
        </View>
      </ScrollView>
    </View>
  );
}

function SectionHeader({
  accessory,
  description,
  title,
}: {
  accessory?: string;
  description: string;
  title: string;
}) {
  return (
    <View style={styles.sectionHeader}>
      <View style={styles.sectionTitleRow}>
        <Text style={styles.sectionTitle}>{title}</Text>
        {accessory ? <Text style={styles.sectionAccessory}>{accessory}</Text> : null}
      </View>
      <Text style={styles.sectionDescription}>{description}</Text>
    </View>
  );
}

function ChoiceRow({
  detail,
  label,
  onPress,
  selected,
}: {
  detail: string;
  label: string;
  onPress: () => void;
  selected: boolean;
}) {
  return (
    <Pressable
      accessibilityLabel={`${label}. ${detail}`}
      accessibilityRole="radio"
      accessibilityState={{checked: selected}}
      onPress={onPress}
      style={({pressed}) => [styles.row, pressed && styles.pressed]}>
      <View style={styles.rowCopy}>
        <Text style={styles.rowLabel}>{label}</Text>
        <Text style={styles.rowDetail}>{detail}</Text>
      </View>
      {selected ? <Check color={colors.text} size={18} strokeWidth={2.2} /> : null}
    </Pressable>
  );
}

function InfoRow({icon: Icon, label, value}: {
  icon: LucideIcon;
  label: string;
  value: string;
}) {
  return (
    <View style={styles.infoRow}>
      <Icon color={colors.textMuted} size={18} strokeWidth={1.7} />
      <Text style={styles.infoLabel}>{label}</Text>
      <Text style={styles.infoValue}>{value}</Text>
    </View>
  );
}

function ActionRow({
  busy,
  destructive = false,
  detail,
  icon: Icon,
  label,
  onPress,
}: {
  busy: boolean;
  destructive?: boolean;
  detail: string;
  icon: LucideIcon;
  label: string;
  onPress: () => void;
}) {
  const tint = destructive ? colors.accent : colors.textMuted;
  return (
    <Pressable
      accessibilityLabel={`${label}. ${detail}`}
      accessibilityRole="button"
      disabled={busy}
      onPress={onPress}
      style={({pressed}) => [styles.row, pressed && styles.pressed]}>
      {busy ? (
        <ActivityIndicator color={tint} size="small" style={styles.actionIcon} />
      ) : (
        <Icon color={tint} size={18} strokeWidth={1.7} style={styles.actionIcon} />
      )}
      <View style={styles.rowCopy}>
        <Text style={[styles.rowLabel, destructive && styles.destructive]}>{label}</Text>
        <Text style={styles.rowDetail}>{detail}</Text>
      </View>
      <ChevronRight color={colors.textSubtle} size={17} strokeWidth={1.8} />
    </Pressable>
  );
}

function clearConfirmation(action: ClearAction) {
  switch (action) {
    case 'media':
      return {
        confirm: 'Clear Media Player',
        message: 'This removes all Media Player downloads, artwork, playlists, and library records. Linked Gallery files are not deleted.',
        title: 'Clear Media Player?',
      };
    case 'reels':
      return {
        confirm: 'Clear snapshots',
        message: 'This permanently deletes every Reel snapshot, downloaded Reel, cover, avatar, and Reel database record.',
        title: 'Clear Reel snapshots?',
      };
    case 'website-cache':
      return {
        confirm: 'Clear cache',
        message: 'Temporary website files will be removed. Your connected-site sign-ins are kept.',
        title: 'Clear website cache?',
      };
    case 'website-data':
      return {
        confirm: 'Reset website data',
        message: 'Cookies, cache, and local website storage will be removed. You will be signed out of Instagram and other connected sites.',
        title: 'Reset website data?',
      };
    case 'all':
      return {
        confirm: 'Clear everything',
        message: 'This permanently removes all app-managed media, databases, Reel snapshots, website data, sign-ins, and playback defaults. Linked Gallery files remain untouched.',
        title: 'Clear all AirplaneMode data?',
      };
  }
}

function clearSuccessMessage(action: ClearAction) {
  if (action === 'media') return 'Media Player cleared';
  if (action === 'reels') return 'Reel snapshots cleared';
  if (action === 'website-cache') return 'Website cache cleared';
  if (action === 'website-data') return 'Website data reset';
  return 'AirplaneMode data cleared';
}

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  const scaled = value / 1024 ** index;
  return `${scaled >= 10 || index === 0 ? scaled.toFixed(0) : scaled.toFixed(1)} ${units[index]}`;
}

const styles = StyleSheet.create({
  actionIcon: {marginRight: spacing.md},
  container: {backgroundColor: colors.canvas, flex: 1},
  content: {paddingBottom: spacing.xxl * 2},
  destructive: {color: colors.accent},
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
  headerTitle: {color: colors.text, fontSize: typography.title, fontWeight: '700'},
  infoLabel: {color: colors.text, flex: 1, fontSize: typography.body, marginLeft: spacing.md},
  infoRow: {
    alignItems: 'center',
    borderBottomColor: colors.border,
    borderBottomWidth: StyleSheet.hairlineWidth,
    flexDirection: 'row',
    minHeight: 50,
    paddingHorizontal: spacing.lg,
  },
  infoValue: {color: colors.textMuted, fontSize: typography.body, fontVariant: ['tabular-nums']},
  pressed: {opacity: 0.58},
  refresh: {
    alignItems: 'center',
    alignSelf: 'flex-start',
    flexDirection: 'row',
    marginLeft: spacing.lg,
    minHeight: layout.minTouchTarget,
  },
  refreshText: {color: colors.accent, fontSize: typography.caption, fontWeight: '700', marginLeft: spacing.sm},
  row: {
    alignItems: 'center',
    borderBottomColor: colors.border,
    borderBottomWidth: StyleSheet.hairlineWidth,
    flexDirection: 'row',
    minHeight: 64,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
  },
  rowCopy: {flex: 1, minWidth: 0, paddingRight: spacing.md},
  rowDetail: {color: colors.textSubtle, fontSize: typography.utility, lineHeight: 15, marginTop: 3},
  rowLabel: {color: colors.text, fontSize: typography.body, fontWeight: '600'},
  section: {
    borderBottomColor: colors.border,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
    borderTopWidth: StyleSheet.hairlineWidth,
    marginTop: spacing.md,
  },
  sectionAccessory: {color: colors.textMuted, fontSize: typography.body, fontVariant: ['tabular-nums']},
  sectionDescription: {color: colors.textMuted, fontSize: typography.caption, lineHeight: 17, marginTop: spacing.xs, maxWidth: 460},
  sectionHeader: {marginTop: spacing.xl, paddingHorizontal: spacing.lg},
  sectionTitle: {color: colors.text, fontSize: typography.title, fontWeight: '700'},
  sectionTitleRow: {alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between'},
  speedChoice: {
    alignItems: 'center',
    borderRadius: radii.round,
    flex: 1,
    justifyContent: 'center',
    minHeight: 36,
  },
  speedChoiceSelected: {backgroundColor: colors.text},
  speedChoiceText: {color: colors.textMuted, fontSize: typography.body, fontWeight: '700'},
  speedChoiceTextSelected: {color: colors.black},
  speedChoices: {
    backgroundColor: colors.surfaceRaised,
    borderRadius: radii.round,
    flex: 1,
    flexDirection: 'row',
    marginLeft: spacing.md,
    padding: 3,
  },
  speedRow: {alignItems: 'center', flexDirection: 'row', marginTop: spacing.md, paddingHorizontal: spacing.lg},
});

export default AppSettingsModule;
