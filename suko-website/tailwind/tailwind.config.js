/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["../build/website/**/*.html"],
  theme: {
    extend: {
      fontFamily: {
        display: ["'Overpass'", "ui-sans-serif", "system-ui", "sans-serif"],
        sans: ["'IBM Plex Sans'", "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["'JetBrains Mono'", "ui-monospace", "SFMono-Regular", "monospace"],
      },
      colors: {
        ink: {
          950: "#0b1220",
        },
      },
    },
  },
  plugins: [],
};
