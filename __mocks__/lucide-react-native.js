const React = require('react');
const {View} = require('react-native');

const MockLucideIcon = props => React.createElement(View, props);

module.exports = new Proxy(
  {__esModule: true},
  {
    get(target, property) {
      if (property === '__esModule') return target.__esModule;
      return MockLucideIcon;
    },
  },
);
