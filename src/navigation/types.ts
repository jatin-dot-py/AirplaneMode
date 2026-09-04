import type {NavigatorScreenParams} from '@react-navigation/native';

export type MediaStackParamList = {
  MediaHome: undefined;
  YouTube: undefined;
  YouTubeMusic: undefined;
};

export type MainTabParamList = {
  Media: NavigatorScreenParams<MediaStackParamList> | undefined;
  Reels: undefined;
  Settings: undefined;
};

export type RootStackParamList = {
  InstagramCapture: {snapshotId: string};
  MainTabs: NavigatorScreenParams<MainTabParamList> | undefined;
  OfflineReels: {snapshotId: string};
};
