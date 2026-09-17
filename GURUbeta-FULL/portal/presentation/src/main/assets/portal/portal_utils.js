/**
 * Portal Utility Functions
 * 
 * Helper functions for portal content to interact with the native app
 * and use the pre-loaded libraries (KaTeX, Mermaid, Chart.js, highlight.js, marked).
 * 
 * Libraries are loaded as separate <script> tags in the portal HTML template:
 * - katex.min.js + auto-render.min.js
 * - mermaid.min.js
 * - chart.umd.min.js
 * - highlight.min.js
 * - marked.min.js
 */

var PortalUtils = {
    /**
     * Send an event from the portal to the native app.
     * @param {string} name - Event name (e.g., 'task_click', 'chart_tap')
     * @param {string} payload - JSON string with event data
     */
    sendEvent: function(name, payload) {
        if (typeof PortalBridge !== 'undefined') {
            PortalBridge.sendEvent(name, typeof payload === 'object' ? JSON.stringify(payload) : payload);
        }
    },

    /**
     * Get a CSS variable value from the portal's theme.
     * @param {string} name - CSS variable name (e.g., '--primary')
     * @returns {string} The CSS value
     */
    getThemeColor: function(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    },

    /**
     * Create a clickable element that sends an event to the native app.
     * @param {string} selector - CSS selector for the element(s)
     * @param {string} eventName - Event name to send
     * @param {function} dataExtractor - Function that extracts data from the element
     */
    makeClickable: function(selector, eventName, dataExtractor) {
        document.querySelectorAll(selector).forEach(function(el) {
            el.style.cursor = 'pointer';
            el.addEventListener('click', function(e) {
                e.preventDefault();
                var data = dataExtractor ? dataExtractor(el) : {};
                PortalUtils.sendEvent(eventName, data);
            });
        });
    },

    /**
     * Render a Mermaid diagram. Call this after inserting .mermaid elements.
     */
    renderMermaid: function() {
        if (typeof mermaid !== 'undefined' && mermaid.run) {
            mermaid.run();
        }
    },

    /**
     * Render a Chart.js chart on a canvas element.
     * @param {string} canvasId - The id of the canvas element
     * @param {object} config - Chart.js configuration
     */
    renderChart: function(canvasId, config) {
        if (typeof Chart !== 'undefined') {
            var canvas = document.getElementById(canvasId);
            if (canvas) {
                new Chart(canvas, config);
            }
        }
    },

    /**
     * Render LaTeX math in the portal content.
     */
    renderMath: function() {
        if (typeof renderMathInElement !== 'undefined') {
            renderMathInElement(document.getElementById('portal-content'), {
                delimiters: [
                    {left: '$$', right: '$$', display: true},
                    {left: '$', right: '$', display: false},
                    {left: '\\(', right: '\\)', display: false},
                    {left: '\\[', right: '\\]', display: true}
                ]
            });
        }
    },

    /**
     * Apply syntax highlighting to all code blocks.
     */
    highlightCode: function() {
        if (typeof hljs !== 'undefined') {
            document.querySelectorAll('pre code').forEach(function(block) {
                hljs.highlightElement(block);
            });
        }
    },

    /**
     * Initialize all portal content renderers.
     * Call this after dynamic content is inserted.
     */
    initAll: function() {
        PortalUtils.renderMermaid();
        PortalUtils.renderMath();
        PortalUtils.highlightCode();
    }
};