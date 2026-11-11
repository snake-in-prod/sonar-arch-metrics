/* Project page "Architecture metrics": the raw arch_* measures with their definitions, and the top offenders. */
window.registerExtension('archmetrics/architecture', function (options) {
  var keys = ['arch_code_lines', 'arch_methods', 'arch_cc_sum', 'arch_cc_max', 'arch_cc_over_count', 'arch_method_lines_sum',
    'arch_class_lines_max', 'arch_cbo_max', 'arch_dup_lines', 'arch_mfs_refs', 'arch_cycle_refs_total', 'arch_cycle_nodes',
    'arch_cycle_nodes_total', 'arch_dep_parents', 'arch_dep_parents_over', 'arch_dep_fanout_max', 'arch_pkg_size_max',
    'arch_nesting_topn_avg', 'arch_unused_classes', 'arch_classes', 'arch_unused_methods', 'arch_methods_total',
    'arch_unused_variables', 'arch_variables_total', 'arch_methods_covered', 'arch_methods_coverable',
    'arch_method_lines_over_count', 'arch_class_lines_over_count', 'arch_cbo_over_count', 'arch_halstead_over_count',
    'arch_halstead_effort_avg', 'arch_global_refs', 'arch_pkg_size_over_count', 'arch_class_mfs_refs', 'arch_class_refs_total',
    'arch_class_cycle_nodes', 'arch_class_nodes', 'arch_top_offenders'];
  var el = document.createElement('div');
  el.setAttribute('style', 'padding:0 20px 20px 20px');
  // SonarQube places extension pages in a grid of two columns: span both, or the page gets only the first one
  options.el.style.gridColumn = '1 / -1';
  options.el.style.width = '100%';
  options.el.appendChild(el);
  var base = (window.baseUrl || '');
  var params = new URLSearchParams({component: options.component.key, metricKeys: keys.join(','), additionalFields: 'metrics'});
  var branch = options.branchLike;
  if (branch && branch.name && !branch.isMain && branch.key === undefined) {
    params.set('branch', branch.name);
  } else if (branch && branch.key !== undefined) {
    params.set('pullRequest', branch.key);
  }

  function text(tag, value, style) {
    var e = document.createElement(tag);
    e.textContent = value;
    if (style) {
      e.setAttribute('style', style);
    }
    return e;
  }

  function table(headers) {
    var t = document.createElement('table');
    t.setAttribute('style', 'border-collapse:collapse;margin:8px 0 24px 0;font-size:13px');
    var tr = document.createElement('tr');
    headers.forEach(function (h) {
      tr.appendChild(text('th', h, 'text-align:left;padding:4px 12px 4px 0;border-bottom:1px solid #ccc'));
    });
    t.appendChild(tr);
    return t;
  }

  function show(value) {
    if (value === undefined || value === null) {
      return '';
    }
    // FLOAT measures come back as "4413528.0" or "1.94743692E9"
    var n = Number(value);
    return /^[-+0-9.Ee]+$/.test(String(value)) && !isNaN(n) ? String(Math.round(n * 100) / 100) : String(value);
  }

  function cell(value, muted) {
    return text('td', muted ? 'n/a' : show(value),
      'padding:4px 12px 4px 0;border-bottom:1px solid #eee;vertical-align:top' + (muted ? ';color:#999' : ''));
  }

  el.appendChild(text('h1', 'Architecture metrics', 'font-size:20px;margin:16px 0'));
  var status = el.appendChild(text('p', 'Loading...'));

  fetch(base + '/api/measures/component?' + params.toString(), {credentials: 'same-origin'})
    .then(function (r) { return r.json(); })
    .then(function (data) {
      status.remove();
      var defs = {};
      (data.metrics || []).forEach(function (m) { defs[m.key] = m; });
      var values = {};
      (data.component.measures || []).forEach(function (m) { values[m.metric] = m.value; });
      if (values.arch_code_lines === undefined && values.arch_classes === undefined) {
        // Python, TypeScript, Dart... projects: no Java analysed, so no measures at all
        el.appendChild(text('p', 'No architecture metrics: this plugin measures Java code.', 'color:#666'));
        return;
      }
      var t = table(['Metric', 'Key', 'Value', 'Definition']);
      keys.filter(function (k) { return k !== 'arch_top_offenders'; }).forEach(function (k) {
        var tr = document.createElement('tr');
        var d = defs[k] || {};
        var missing = values[k] === undefined; // e.g. coverage measures without a JaCoCo report
        tr.appendChild(cell(d.name || k));
        tr.appendChild(cell(k));
        tr.appendChild(cell(values[k], missing));
        tr.appendChild(cell(d.description));
        t.appendChild(tr);
      });
      el.appendChild(t);

      var top = values.arch_top_offenders ? JSON.parse(values.arch_top_offenders) : {};
      Object.keys(top).forEach(function (k) {
        if (!top[k] || top[k].length === 0) {
          return;
        }
        el.appendChild(text('h2', 'Behind ' + ((defs[k] || {}).name || k), 'font-size:16px;margin:12px 0 4px 0'));
        var tt = table(['Name', 'File', 'Line', 'Value']);
        top[k].forEach(function (row) {
          var tr = document.createElement('tr');
          [row.name, row.file, row.line, row.value].forEach(function (v) { tr.appendChild(cell(v)); });
          tt.appendChild(tr);
        });
        el.appendChild(tt);
      });
    })
    .catch(function (e) { status.textContent = 'Cannot load the measures: ' + e; });

  return function () {
    options.el.innerHTML = '';
  };
});
