/// <reference types="vite/client" />

declare module '*.css' {
  const content: string;
  export default content;
}

declare module 'katex/dist/katex.min.css';
declare module 'diff2html/bundles/css/diff2html.min.css';
declare module 'react-simple-maps';
