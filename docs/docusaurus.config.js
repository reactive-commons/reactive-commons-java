// @ts-check
// `@type` JSDoc annotations allow editor autocompletion and type checking
// (when paired with `@ts-check`).
// There are various equivalent ways to declare your Docusaurus config.
// See: https://docusaurus.io/docs/api/docusaurus-config

import {themes as prismThemes} from 'prism-react-renderer';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Reactive Commons Java',
  tagline: 'The purpose of reactive-commons is to provide a set of abstractions and implementations over different patterns and practices that make the foundation of a reactive microservices architecture',
  favicon: 'img/favicon.ico',

  // Set the production url of your site here
  url: 'https://reactivecommons.org',
  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: '/reactive-commons-java/',

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'reactive-commons', // Usually your GitHub org/user name.
  projectName: 'reactive-commons-java', // Usually your repo name.

  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: './sidebars.js',
          // Please change this to your repo.
          // Remove this to remove the "edit this page" links.
          editUrl:
          'https://github.com/reactive-commons/reactive-commons-java/tree/docs/docs',
        },
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      // Replace with your project's social card
      image: 'docs/img/reactive-commons.png',
      navbar: {
        title: 'Reactive Commmons Java',
        logo: {
          alt: 'Reactive Commmons Java Logo',
          src: 'img/logo.svg',
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'tutorialSidebar',
            position: 'left',
            label: 'Docs',
          },
          {
            href: 'https://medium.com/bancolombia-tech/potenciando-la-arquitectura-de-microservicios-reactivos-88fc84ae0b7d',
            label: 'Blog',
            position: 'right',
          },
          {
            href: 'https://github.com/reactive-commons/reactive-commons-java',
            label: 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Docs',
            items: [
              {
                label: 'Overview',
                to: '/docs/intro',
              },
              {
                label: 'Reactive Commons',
                to: '/docs/reactive-commons/getting-started',
              },
              {
                label: 'Reactive Commons EDA',
                to: '/docs/reactive-commons-eda/getting-started',
              },
            ],
          },
          {
            title: 'Community',
            items: [
              {
                label: 'Changelog',
                href: 'https://github.com/reactive-commons/reactive-commons-java/blob/master/CHANGELOG.md',
              },
              // {
              //   label: 'Contributing',
              //   href: 'https://github.com/reactive-commons/reactive-commons-java/blob/master/CONTRIBUTING.md',
              // },
              // {
              //   label: 'Troubleshooting',
              //   href: 'https://github.com/reactive-commons/reactive-commons-java/blob/master/TROUBLESHOOTING.md',
              // },
            ],
          },
          {
            title: 'More',
            items: [
              {
                label: 'Bancolombia Tech',
                href: 'https://medium.com/bancolombia-tech',
              },
              {
                label: 'GitHub',
                href: 'https://github.com/reactive-commons/reactive-commons-java',
              },
              {
                label: 'Maven Central',
                href: 'https://central.sonatype.com/artifact/org.reactivecommons/async-commons-rabbit-starter/versions',
              },
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} Reactive Commons.`,
      },
      prism: {
        additionalLanguages: ['java', 'groovy', 'yaml'],
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
      },
    }),
};

export default config;
