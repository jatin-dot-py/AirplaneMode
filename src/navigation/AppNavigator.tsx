import React from 'react';
import {StatusBar, StyleSheet, View} from 'react-native';
import {
  createBottomTabNavigator,
  type BottomTabScreenProps,
  type BottomTabNavigationOptions,
} from '@react-navigation/bottom-tabs';
import {
  createNativeStackNavigator,
  type NativeStackNavigationProp,
  type NativeStackScreenProps,
} from '@react-navigation/native-stack';
import {
  Clapperboard,
  Library,
  Settings as SettingsIcon,
  type LucideIcon,
} from 'lucide-react-native';
import {SafeAreaView} from 'react-native-safe-area-context';

import DoomscrollerModule, {
  InstagramCaptureSurface,
} from '../modules/doomscroller/DoomscrollerModule';
import OfflineReelsSurface from '../modules/doomscroller/OfflineReelsSurface';
import MediaPlayerModule from '../modules/media-player/MediaPlayerModule';
import AppSettingsModule from '../modules/settings/AppSettingsModule';
import {colors, typography} from '../theme';
import type {
  MainTabParamList,
  RootStackParamList,
} from './types';

const INSTAGRAM_HOME_URL = 'https://www.instagram.com/';
const RootStack = createNativeStackNavigator<RootStackParamList>();
const MainTabs = createBottomTabNavigator<MainTabParamList>();

const tabIcons: Record<keyof MainTabParamList, LucideIcon> = {
  Media: Library,
  Reels: Clapperboard,
  Settings: SettingsIcon,
};

function AppNavigator() {
  return (
    <RootStack.Navigator
      initialRouteName="MainTabs"
      screenOptions={{
        animation: 'slide_from_right',
        contentStyle: styles.rootScene,
        headerShown: false,
      }}>
      <RootStack.Screen component={MainTabNavigator} name="MainTabs" />
      <RootStack.Screen
        component={InstagramCaptureScreen}
        name="InstagramCapture"
      />
      <RootStack.Screen
        component={OfflineReelsScreen}
        name="OfflineReels"
        options={{animation: 'fade'}}
      />
    </RootStack.Navigator>
  );
}

function MainTabNavigator() {
  return (
    <MainTabs.Navigator
      backBehavior="initialRoute"
      initialRouteName="Media"
      screenOptions={({route}) => tabOptions(route.name)}>
      <MainTabs.Screen component={MediaPlayerModule} name="Media" />
      <MainTabs.Screen component={ReelSnapshotsScreen} name="Reels" />
      <MainTabs.Screen component={SettingsScreen} name="Settings" />
    </MainTabs.Navigator>
  );
}

function ReelSnapshotsScreen({
  navigation,
}: BottomTabScreenProps<MainTabParamList, 'Reels'>) {
  const rootNavigation = navigation.getParent<
    NativeStackNavigationProp<RootStackParamList>
  >();
  const openRoot = (
    target: 'InstagramCapture' | 'OfflineReels',
    snapshotId: string,
  ) => rootNavigation?.navigate(target, {snapshotId});

  return (
    <SafeAreaView edges={['top']} style={styles.safeModule}>
      <DoomscrollerModule
        onCapture={snapshotId => openRoot('InstagramCapture', snapshotId)}
        onOpenSnapshot={snapshotId => openRoot('OfflineReels', snapshotId)}
      />
    </SafeAreaView>
  );
}

function SettingsScreen() {
  return (
    <SafeAreaView edges={['top']} style={styles.safeModule}>
      <AppSettingsModule />
    </SafeAreaView>
  );
}

function InstagramCaptureScreen({
  navigation,
  route,
}: NativeStackScreenProps<RootStackParamList, 'InstagramCapture'>) {
  const {snapshotId} = route.params;
  return (
    <SafeAreaView edges={['top', 'bottom']} style={styles.safeModule}>
      <InstagramCaptureSurface
        homeUrl={INSTAGRAM_HOME_URL}
        onBack={() => navigation.goBack()}
        onOpenSnapshot={() => navigation.replace('OfflineReels', {snapshotId})}
        snapshotId={snapshotId}
      />
    </SafeAreaView>
  );
}

function OfflineReelsScreen({
  navigation,
  route,
}: NativeStackScreenProps<RootStackParamList, 'OfflineReels'>) {
  return (
    <View style={styles.fullscreen}>
      <StatusBar hidden />
      <OfflineReelsSurface
        onBack={() => navigation.goBack()}
        snapshotId={route.params.snapshotId}
      />
    </View>
  );
}

function tabOptions(name: keyof MainTabParamList): BottomTabNavigationOptions {
  const Icon = tabIcons[name];
  return {
    headerShown: false,
    sceneStyle: styles.tabScene,
    tabBarActiveTintColor: colors.text,
    tabBarHideOnKeyboard: true,
    tabBarIcon: ({color, size}) => (
      <Icon color={color} size={Math.min(size, 22)} strokeWidth={1.8} />
    ),
    tabBarInactiveTintColor: colors.textSubtle,
    tabBarItemStyle: styles.tabItem,
    tabBarLabelStyle: styles.tabLabel,
    tabBarStyle: styles.tabBar,
  };
}

const styles = StyleSheet.create({
  fullscreen: {backgroundColor: colors.black, flex: 1},
  rootScene: {backgroundColor: colors.canvas},
  safeModule: {backgroundColor: colors.canvas, flex: 1},
  tabBar: {
    backgroundColor: colors.surface,
    borderTopColor: colors.border,
    borderTopWidth: StyleSheet.hairlineWidth,
    elevation: 0,
  },
  tabItem: {paddingVertical: 2},
  tabLabel: {
    fontSize: typography.utility,
    fontWeight: '600',
    letterSpacing: 0.1,
  },
  tabScene: {backgroundColor: colors.canvas},
});

export default AppNavigator;
