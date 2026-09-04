import React, {useEffect, useState} from 'react';
import {
  Image,
  StyleSheet,
  View,
  type ImageStyle,
  type StyleProp,
  type ViewStyle,
} from 'react-native';

import {colors} from '../../theme';

const fallbackArtwork = require('../../assets/icons/music-note.png');

function MediaArtwork({
  path,
  style,
  imageStyle,
  fallbackStyle,
}: {
  path: string | null;
  style?: StyleProp<ViewStyle>;
  imageStyle?: StyleProp<ImageStyle>;
  fallbackStyle?: StyleProp<ImageStyle>;
}) {
  const [failed, setFailed] = useState(false);

  useEffect(() => setFailed(false), [path]);

  const hasArtwork = Boolean(path) && !failed;
  return (
    <View style={[styles.container, style]}>
      <Image
        accessibilityIgnoresInvertColors
        onError={() => setFailed(true)}
        resizeMode={hasArtwork ? 'cover' : 'contain'}
        source={hasArtwork ? {uri: `file://${path}`} : fallbackArtwork}
        style={[
          styles.image,
          imageStyle,
          !hasArtwork && styles.fallback,
          !hasArtwork && fallbackStyle,
        ]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    backgroundColor: colors.surfaceRaised,
    justifyContent: 'center',
    overflow: 'hidden',
  },
  image: {height: '100%', width: '100%'},
  fallback: {height: '38%', opacity: 0.72, width: '38%'},
});

export default MediaArtwork;
