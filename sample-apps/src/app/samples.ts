export interface SampleDefinition {
  id: string;
  title: string;
  eyebrow: string;
  pill: string;
  summary: string;
  successMarker: string;
  route: string;
  hasFavicon: boolean;
}

export const SAMPLES: SampleDefinition[] = [
  {
    id: 'swing-gui',
    title: 'Swing GUI',
    eyebrow: 'Desktop window',
    pill: 'JDK 17 · JNLP · javaws',
    summary: 'Minimal JDK 17 Swing app for manual launch and JVM selection checks.',
    successMarker: 'ITW_SAMPLE_APP_1_SUCCESS gui jdk=',
    route: '/samples/swing-gui',
    hasFavicon: false,
  },
  {
    id: 'console',
    title: 'Console',
    eyebrow: 'Headless-friendly · no JNLP favicon',
    pill: 'JDK 17 · javawsc',
    summary: 'Stdout harness for asserting IcedTea-Web console launch and favicon INFO logging.',
    successMarker: 'ITW_SAMPLE_APP_2_SUCCESS console jdk=',
    route: '/samples/console',
    hasFavicon: false,
  },
];

export function jnlpUrl(sampleId: string, host = '127.0.0.1:4200'): string {
  return `http://${host}/jnlp/${sampleId}/app.jnlp`;
}

export function findSample(id: string): SampleDefinition | undefined {
  return SAMPLES.find((sample) => sample.id === id);
}
