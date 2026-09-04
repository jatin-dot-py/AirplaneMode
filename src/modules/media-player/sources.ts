import type {MediaSource} from './types';

export const mediaSources: MediaSource[] = [
  {
    id: 'library',
    name: 'Library',
    shortName: 'Library',
    icon: require('../../assets/media-sources/library.png'),
    description: 'Everything queued, imported, and available offline.',
    implementation: {type: 'library'},
  },
  {
    id: 'youtube-music',
    name: 'YouTube Music',
    shortName: 'YT Music',
    icon: require('../../assets/media-sources/youtube-music.png'),
    description: 'Browse your account and collect detected music.',
    implementation: {
      type: 'youtube-music-scanner',
      homeUrl: 'https://music.youtube.com/',
    },
  },
  {
    id: 'youtube',
    name: 'YouTube',
    shortName: 'YouTube',
    icon: require('../../assets/media-sources/youtube.png'),
    description: 'Open YouTube on the web.',
    implementation: {type: 'website', homeUrl: 'https://m.youtube.com/'},
  },
  {
    id: 'gallery',
    name: 'Gallery',
    shortName: 'Gallery',
    icon: require('../../assets/media-sources/gallery.png'),
    description: 'Import audio and video already on this device.',
    implementation: {type: 'gallery-import'},
  },
];
