import React from 'react';
import { useColorMode } from '@docusaurus/theme-common';

const scenarios = {
    '1': {
        dark: require('@site/static/img/scenarios/1-scenario-dark.svg').default,
        light: require('@site/static/img/scenarios/1-scenario.svg').default
    },
    '2': {
        dark: require('@site/static/img/scenarios/2-scenario-dark.svg').default,
        light: require('@site/static/img/scenarios/2-scenario.svg').default
    },
//    '3': {
//        dark: require('@site/static/img/scenarios/3-scenario-dark.svg').default,
//        light: require('@site/static/img/scenarios/3-scenario.svg').default
//    },
}

console.log(scenarios)

const ThemeImage = ({ scenario }) => {
  const { colorMode } = useColorMode();

  // Import the images using `require`
  const Svg = scenarios[scenario][colorMode];

  return (
    <Svg role="img" width="100%" height="100%"/>
  );
};

export default ThemeImage;
