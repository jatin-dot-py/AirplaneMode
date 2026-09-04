import React from 'react';
import {StatusBar} from 'react-native';
import {
  DarkTheme,
  NavigationContainer,
  type Theme,
} from '@react-navigation/native';
import {SafeAreaProvider} from 'react-native-safe-area-context';

import AppNavigator from './src/navigation/AppNavigator';
import {PlayerOverlayProvider} from './src/modules/media-player/MiniPlayer';
import {colors} from './src/theme';

const navigationTheme: Theme = {
  ...DarkTheme,
  colors: {
    ...DarkTheme.colors,
    background: colors.canvas,
    border: colors.border,
    card: colors.surface,
    notification: colors.accent,
    primary: colors.text,
    text: colors.text,
  },
};

function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <PlayerOverlayProvider>
        <NavigationContainer theme={navigationTheme}>
          <AppNavigator />
        </NavigationContainer>
      </PlayerOverlayProvider>
    </SafeAreaProvider>
  );
}

export default App;
