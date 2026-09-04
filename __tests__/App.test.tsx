/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

jest.mock('react-native-webview', () => {
  const mockReact = require('react');
  const {View: MockView} = require('react-native');

  return {
    __esModule: true,
    default: mockReact.forwardRef(() =>
      mockReact.createElement(MockView, {testID: 'youtube-music-webview'}),
    ),
  };
});

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});
