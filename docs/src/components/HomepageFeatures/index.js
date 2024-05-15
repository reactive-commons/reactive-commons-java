import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

const FeatureList = [
  {
    title: 'Easy to Use',
    Svg: require('@site/static/img/feature-first.svg').default,
    description: (
      <>
        Reactive Commons comes with starter setup to start quickly and abstract your Broker.
      </>
    ),
  },
  {
    title: 'Focus on Domain',
    Svg: require('@site/static/img/feature-second.svg').default,
    description: (
      <>
        Reactive Commons lets you focus on your domain solution abstracting the communication
        details with the Broker and giving you a semantic specification.
      </>
    ),
  },
  {
    title: 'Powered by Reactor and Reactive Streams',
    Svg: require('@site/static/img/feature-third.svg').default,
    description: (
      <>
        Get the reactive programming benefits out of the box.
      </>
    ),
  },
];

function Feature({Svg, title, description}) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center">
        <Svg className={styles.featureSvg} role="img" />
      </div>
      <div className="text--center padding-horiz--md">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures() {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
