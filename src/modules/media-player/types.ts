import type {ImageSourcePropType} from 'react-native';

export type MediaSurfaceId =
  | 'library'
  | 'youtube-music'
  | 'gallery'
  | 'youtube';

export type MediaSourceImplementation =
  | {type: 'library'}
  | {type: 'youtube-music-scanner'; homeUrl: string}
  | {type: 'gallery-import'}
  | {type: 'website'; homeUrl: string};

export type MediaSource = {
  id: MediaSurfaceId;
  name: string;
  shortName: string;
  icon: ImageSourcePropType;
  description: string;
  implementation: MediaSourceImplementation;
};

export type LibraryFilter = 'all' | 'queued' | 'ready' | 'imported';
