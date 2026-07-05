'use strict';

function clampRange(v, min, max) {
  v = Number(v);
  if (!isFinite(v)) v = min;
  return Math.max(min, Math.min(max, v));
}
function normalizeLyricFontKey(value) {
  var key = String(value || 'hei');
  return /^(hei|round|mono|display|stone-song)$/.test(key) ? key : 'hei';
}
function lyricFontStackForKey(key) {
  key = normalizeLyricFontKey(key);
  if (key === 'stone-song') return '"Microsoft YaHei UI","Noto Sans SC","PingFang SC",sans-serif';
  if (key === 'round') return '"HarmonyOS Sans SC","Microsoft YaHei UI","PingFang SC","Noto Sans SC",sans-serif';
  if (key === 'mono') return '"JetBrains Mono",Consolas,"Noto Sans SC","Microsoft YaHei",monospace';
  if (key === 'display') return '"Alibaba PuHuiTi","Noto Sans SC","PingFang SC","Microsoft YaHei",sans-serif';
  return 'Inter,"Noto Sans SC","PingFang SC","Microsoft YaHei",Arial,sans-serif';
}
function normalizeStyle(style) {
  style = style || {};
  var fontKey = normalizeLyricFontKey(style.fontKey);
  return {
    fontKey: fontKey,
    weight: fontKey === 'stone-song' ? 900 : Math.round(clampRange(style.weight || 900, 500, 900) / 50) * 50,
    letterSpacing: clampRange(style.letterSpacing || 0, -0.04, 0.18),
    lineHeight: clampRange(style.lineHeight || 1, 0.86, 1.35),
    maxLines: Math.max(1, Math.min(2, Math.round(Number(style.maxLines) || 1)))
  };
}
function lyricFontCss(fontSize, style) {
  return style.weight + ' ' + fontSize + 'px ' + lyricFontStackForKey(style.fontKey);
}
function lyricLetterSpacingPx(fontSize, style) {
  return style.letterSpacing * Math.max(1, fontSize || 1);
}
function measureTextWithLetterSpacing(ctx, text, spacing) {
  text = String(text || '');
  spacing = Number(spacing) || 0;
  if (!spacing || text.length < 2) return ctx.measureText(text).width;
  var chars = Array.from(text);
  var w = 0;
  for (var i = 0; i < chars.length; i++) {
    w += ctx.measureText(chars[i]).width;
    if (i < chars.length - 1) w += spacing;
  }
  return Math.max(1, w);
}
function lyricMeasureText(ctx, text, fontSize, style) {
  return measureTextWithLetterSpacing(ctx, text, lyricLetterSpacingPx(fontSize, style));
}
function drawTextWithLetterSpacing(ctx, text, x, y, spacing, stroke) {
  text = String(text || '');
  spacing = Number(spacing) || 0;
  if (!spacing || text.length < 2) {
    if (stroke) ctx.strokeText(text, x, y);
    else ctx.fillText(text, x, y);
    return;
  }
  var chars = Array.from(text);
  var align = ctx.textAlign || 'left';
  var width = measureTextWithLetterSpacing(ctx, text, spacing);
  var start = x;
  if (align === 'center') start = x - width / 2;
  else if (align === 'right' || align === 'end') start = x - width;
  ctx.textAlign = 'left';
  var cursor = start;
  for (var i = 0; i < chars.length; i++) {
    if (stroke) ctx.strokeText(chars[i], cursor, y);
    else ctx.fillText(chars[i], cursor, y);
    cursor += ctx.measureText(chars[i]).width + (i < chars.length - 1 ? spacing : 0);
  }
  ctx.textAlign = align;
}
function lyricFillText(ctx, text, x, y, fontSize, style) {
  drawTextWithLetterSpacing(ctx, text, x, y, lyricLetterSpacingPx(fontSize, style), false);
}
function lyricStrokeText(ctx, text, x, y, fontSize, style) {
  drawTextWithLetterSpacing(ctx, text, x, y, lyricLetterSpacingPx(fontSize, style), true);
}
function makeCanvas(w, h) {
  if (typeof OffscreenCanvas === 'undefined') throw new Error('OffscreenCanvas unavailable');
  return new OffscreenCanvas(w, h);
}
function wrapLyricText(ctx, text, maxWidth, maxLines, fontSize, style) {
  text = String(text || '').trim();
  var useWords = /\s/.test(text) && /[A-Za-z0-9]/.test(text);
  var units = useWords ? text.split(/(\s+)/).filter(Boolean) : text.split('');
  var lines = [], line = '';
  for (var i = 0; i < units.length; i++) {
    var test = line + units[i];
    if (lyricMeasureText(ctx, test, fontSize, style) > maxWidth && line) {
      lines.push(line.trim());
      line = units[i].trimStart ? units[i].trimStart() : units[i].replace(/^\s+/, '');
      if (lines.length >= maxLines) {
        var rest = units.slice(i).join('').trim();
        if (rest) lines[lines.length - 1] = lines[lines.length - 1].replace(/[.。,…，、\s]*$/, '') + '...';
        return lines;
      }
    } else {
      line = test;
    }
  }
  if (line && lines.length < maxLines) lines.push(line.trim());
  return lines.length ? lines : [''];
}
function applyStonePrintTexture(ctx, W, H, fontSize, style) {
  if (style.fontKey !== 'stone-song') return;
  var size = clampRange(fontSize || 128, 42, 180);
  var bandTop = H * 0.10;
  var bandH = H * 0.80;
  ctx.save();
  ctx.globalCompositeOperation = 'destination-out';

  var noiseW = 300, noiseH = 110;
  var noise = makeCanvas(noiseW, noiseH);
  var nctx = noise.getContext('2d');
  var img = nctx.createImageData(noiseW, noiseH);
  for (var p = 0; p < noiseW * noiseH; p++) {
    var x0 = p % noiseW;
    var y0 = Math.floor(p / noiseW);
    var vein = Math.sin(x0 * 0.19 + y0 * 0.043) * 0.10 + Math.sin(y0 * 0.31) * 0.06;
    var r = Math.random() + vein;
    var a = 0;
    if (r > 0.82) a = 78 + Math.random() * 92;
    else if (r > 0.62) a = 22 + Math.random() * 54;
    else if (r > 0.48) a = 4 + Math.random() * 24;
    img.data[p * 4] = 255;
    img.data[p * 4 + 1] = 255;
    img.data[p * 4 + 2] = 255;
    img.data[p * 4 + 3] = a;
  }
  nctx.putImageData(img, 0, 0);
  ctx.imageSmoothingEnabled = false;
  ctx.globalAlpha = 0.34;
  ctx.drawImage(noise, 0, bandTop, W, bandH);

  var chips = Math.round(size * 7.2);
  for (var i = 0; i < chips; i++) {
    var x = Math.random() * W;
    var y = bandTop + Math.random() * bandH;
    var w = 0.7 + Math.random() * (size * 0.052);
    var h = 0.45 + Math.random() * (size * 0.026);
    ctx.globalAlpha = 0.16 + Math.random() * 0.36;
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate((Math.random() - 0.5) * 0.38);
    ctx.fillRect(-w / 2, -h / 2, w, h);
    ctx.restore();
  }

  ctx.lineCap = 'round';
  for (var s = 0; s < 44; s++) {
    var sx = Math.random() * W;
    var sy = bandTop + Math.random() * bandH;
    ctx.globalAlpha = 0.09 + Math.random() * 0.16;
    ctx.lineWidth = 0.45 + Math.random() * 1.2;
    ctx.beginPath();
    ctx.moveTo(sx, sy);
    ctx.lineTo(sx + 10 + Math.random() * 86, sy + (Math.random() - 0.5) * 4.8);
    ctx.stroke();
  }

  for (var c = 0; c < 26; c++) {
    var cx = Math.random() * W;
    var cy = bandTop + Math.random() * bandH;
    var radius = 1.8 + Math.random() * (size * 0.060);
    ctx.globalAlpha = 0.08 + Math.random() * 0.18;
    ctx.beginPath();
    ctx.ellipse(cx, cy, radius * (0.7 + Math.random() * 1.4), radius * (0.25 + Math.random() * 0.55), Math.random() * Math.PI, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.restore();
}
function packCanvas(canvas, transfer) {
  var ctx = canvas.getContext('2d');
  var imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
  transfer.push(imageData.data.buffer);
  return { kind: 'imageData', imageData: imageData, width: canvas.width, height: canvas.height };
}
function renderMask(text, style) {
  var canvas = makeCanvas(2048, 384);
  var W = canvas.width, H = canvas.height;
  var ctx = canvas.getContext('2d');
  var maxWidth = W - 190;
  var maxLines = style.maxLines || 1;
  var fontSize = 128;
  text = String(text || '').replace(/\s+/g, ' ').trim();
  var lines = [text];
  var widest = 1;
  for (; fontSize >= 42; fontSize -= 4) {
    ctx.font = lyricFontCss(fontSize, style);
    lines = maxLines > 1 && lyricMeasureText(ctx, text, fontSize, style) > maxWidth ? wrapLyricText(ctx, text, maxWidth, maxLines, fontSize, style) : [text];
    widest = 1;
    for (var li = 0; li < lines.length; li++) widest = Math.max(widest, lyricMeasureText(ctx, lines[li], fontSize, style));
    if (widest <= maxWidth) break;
  }
  ctx.font = lyricFontCss(fontSize, style);
  if (!lines.length) lines = [''];
  widest = 1;
  for (var mi = 0; mi < lines.length; mi++) widest = Math.max(widest, lyricMeasureText(ctx, lines[mi], fontSize, style));
  var width = Math.min(maxWidth, widest);
  var fitScaleX = maxLines <= 1 && widest > maxWidth ? Math.max(0.68, maxWidth / widest) : 1;
  if (fitScaleX < 1) width = Math.min(maxWidth, widest * fitScaleX);
  var lineHeight = fontSize * (lines.length > 1 ? 1.02 : 1.0) * style.lineHeight;
  var blockH = fontSize + (lines.length - 1) * lineHeight;
  var x = W / 2, y0 = H / 2 - blockH / 2 + fontSize * 0.82;
  ctx.clearRect(0, 0, W, H);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'alphabetic';
  ctx.fillStyle = '#fff';
  for (var di = 0; di < lines.length; di++) {
    if (fitScaleX < 1) {
      ctx.save();
      ctx.translate(x, 0);
      ctx.scale(fitScaleX, 1);
      lyricFillText(ctx, lines[di], 0, y0 + di * lineHeight, fontSize, style);
      ctx.restore();
    } else {
      lyricFillText(ctx, lines[di], x, y0 + di * lineHeight, fontSize, style);
    }
  }
  applyStonePrintTexture(ctx, W, H, fontSize, style);
  return {
    canvas: canvas,
    meta: {
      width: W,
      height: H,
      textWidth: width,
      textHeight: blockH,
      fontSize: fontSize,
      lineHeight: lineHeight,
      lineCount: lines.length,
      lines: lines,
      fitScaleX: fitScaleX,
      textMin: (W / 2 - width / 2) / W,
      textMax: (W / 2 + width / 2) / W
    }
  };
}
function renderReadability(maskMeta, style) {
  var canvas = makeCanvas(maskMeta.width || 2048, maskMeta.height || 384);
  var W = canvas.width, H = canvas.height;
  var fontSize = maskMeta.fontSize || 128;
  var lines = Array.isArray(maskMeta.lines) && maskMeta.lines.length ? maskMeta.lines : [''];
  var lineHeight = maskMeta.lineHeight || fontSize * style.lineHeight;
  var fitScaleX = maskMeta.fitScaleX || 1;
  var ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, W, H);
  ctx.font = lyricFontCss(fontSize, style);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'alphabetic';
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.miterLimit = 2;
  var blockH = fontSize + (lines.length - 1) * lineHeight;
  var y0 = H / 2 - blockH / 2 + fontSize * 0.82;
  function strokeLines(dx, dy) {
    for (var i = 0; i < lines.length; i++) {
      var y = y0 + i * lineHeight + (dy || 0);
      if (fitScaleX < 1) {
        ctx.save();
        ctx.translate(W / 2 + (dx || 0), 0);
        ctx.scale(fitScaleX, 1);
        lyricStrokeText(ctx, lines[i], 0, y, fontSize, style);
        ctx.restore();
      } else {
        lyricStrokeText(ctx, lines[i], W / 2 + (dx || 0), y, fontSize, style);
      }
    }
  }
  ctx.save();
  ctx.filter = 'blur(14px)';
  ctx.globalAlpha = 0.18;
  ctx.lineWidth = Math.max(18, fontSize * 0.16);
  ctx.strokeStyle = 'rgba(0,0,0,1)';
  strokeLines(0, fontSize * 0.018);
  ctx.restore();
  ctx.save();
  ctx.filter = 'blur(5px)';
  ctx.globalAlpha = 0.32;
  ctx.lineWidth = Math.max(9, fontSize * 0.075);
  ctx.strokeStyle = 'rgba(0,0,0,1)';
  strokeLines(0, fontSize * 0.012);
  ctx.restore();
  ctx.save();
  ctx.filter = 'blur(4px)';
  ctx.globalAlpha = 0.15;
  ctx.lineWidth = Math.max(9, fontSize * 0.070);
  ctx.strokeStyle = 'rgba(255,255,255,1)';
  strokeLines(0, 0);
  ctx.restore();
  ctx.save();
  ctx.filter = 'blur(1.2px)';
  ctx.globalAlpha = 0.26;
  ctx.lineWidth = Math.max(3.2, fontSize * 0.030);
  ctx.strokeStyle = 'rgba(255,255,255,1)';
  strokeLines(0, 0);
  ctx.restore();
  return canvas;
}
function renderGlow(text, maskMeta, style) {
  text = String(text || '').replace(/\s+/g, ' ').trim();
  var drawLines = Array.isArray(maskMeta.lines) && maskMeta.lines.length ? maskMeta.lines : [text];
  var measureCanvas = makeCanvas(1, 1);
  var measureCtx = measureCanvas.getContext('2d');
  var fontSize = maskMeta.fontSize || 128;
  measureCtx.font = lyricFontCss(fontSize, style);
  var fitScaleX = maskMeta.fitScaleX || 1;
  var measuredWidth = Math.max(1, maskMeta.textWidth || lyricMeasureText(measureCtx, text, fontSize, style) * fitScaleX);
  for (var li = 0; li < drawLines.length; li++) measuredWidth = Math.max(measuredWidth, lyricMeasureText(measureCtx, drawLines[li], fontSize, style) * fitScaleX);
  var padX = Math.max(320, fontSize * 2.70);
  var padY = Math.max(230, fontSize * 1.92);
  var lh = maskMeta.lineHeight || fontSize * 1.04;
  var blockH = fontSize + (drawLines.length - 1) * lh;
  var W = Math.ceil(measuredWidth + padX * 2);
  var H = Math.ceil(blockH + padY * 2);
  var canvas = makeCanvas(W, H);
  var ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, W, H);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'alphabetic';
  ctx.font = lyricFontCss(fontSize, style);
  var y0 = H / 2 - blockH / 2 + fontSize * 0.82;
  function drawGlowText(dx, dy) {
    for (var i = 0; i < drawLines.length; i++) {
      var y = y0 + i * lh + (dy || 0);
      if (fitScaleX < 1) {
        ctx.save();
        ctx.translate(W / 2 + (dx || 0), 0);
        ctx.scale(fitScaleX, 1);
        if (ctx.lineWidth > 0) lyricStrokeText(ctx, drawLines[i], 0, y, fontSize, style);
        lyricFillText(ctx, drawLines[i], 0, y, fontSize, style);
        ctx.restore();
      } else {
        if (ctx.lineWidth > 0) lyricStrokeText(ctx, drawLines[i], W / 2 + (dx || 0), y, fontSize, style);
        lyricFillText(ctx, drawLines[i], W / 2 + (dx || 0), y, fontSize, style);
      }
    }
  }
  ctx.save();
  ctx.filter = 'blur(14px)';
  ctx.globalAlpha = 0.46;
  ctx.fillStyle = '#fff';
  ctx.lineWidth = Math.max(10, fontSize * 0.10);
  ctx.strokeStyle = '#fff';
  drawGlowText(0, 0);
  ctx.restore();
  ctx.save();
  ctx.filter = 'blur(34px)';
  ctx.globalAlpha = 0.34;
  ctx.fillStyle = '#fff';
  ctx.lineWidth = Math.max(18, fontSize * 0.18);
  ctx.strokeStyle = '#fff';
  drawGlowText(0, 0);
  ctx.restore();
  ctx.save();
  ctx.filter = 'blur(78px)';
  ctx.globalAlpha = 0.22;
  ctx.fillStyle = '#fff';
  ctx.lineWidth = Math.max(28, fontSize * 0.26);
  ctx.strokeStyle = '#fff';
  drawGlowText(0, 0);
  ctx.restore();
  ctx.save();
  ctx.filter = 'blur(104px)';
  ctx.globalAlpha = 0.12;
  ctx.fillStyle = '#fff';
  ctx.lineWidth = Math.max(42, fontSize * 0.40);
  ctx.strokeStyle = '#fff';
  drawGlowText(0, 0);
  ctx.restore();
  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  ctx.filter = 'blur(8px)';
  ctx.globalAlpha = 0.26;
  ctx.fillStyle = '#fff';
  for (var ri = 0; ri < 8; ri++) {
    var ang = ri / 8 * Math.PI * 2;
    drawGlowText(Math.cos(ang) * 7, Math.sin(ang) * 4);
  }
  ctx.restore();
  ctx.save();
  ctx.globalCompositeOperation = 'destination-in';
  var xMask = ctx.createLinearGradient(0, 0, W, 0);
  xMask.addColorStop(0.00, 'rgba(255,255,255,0)');
  xMask.addColorStop(0.06, 'rgba(255,255,255,.24)');
  xMask.addColorStop(0.18, 'rgba(255,255,255,1)');
  xMask.addColorStop(0.82, 'rgba(255,255,255,1)');
  xMask.addColorStop(0.94, 'rgba(255,255,255,.24)');
  xMask.addColorStop(1.00, 'rgba(255,255,255,0)');
  ctx.fillStyle = xMask;
  ctx.fillRect(0, 0, W, H);
  var yMask = ctx.createLinearGradient(0, 0, 0, H);
  yMask.addColorStop(0.00, 'rgba(255,255,255,0)');
  yMask.addColorStop(0.08, 'rgba(255,255,255,.22)');
  yMask.addColorStop(0.24, 'rgba(255,255,255,1)');
  yMask.addColorStop(0.76, 'rgba(255,255,255,1)');
  yMask.addColorStop(0.92, 'rgba(255,255,255,.22)');
  yMask.addColorStop(1.00, 'rgba(255,255,255,0)');
  ctx.fillStyle = yMask;
  ctx.fillRect(0, 0, W, H);
  ctx.restore();
  return { canvas: canvas, meta: { width: W, height: H, textWidth: measuredWidth } };
}
function renderPayload(text, style) {
  style = normalizeStyle(style);
  var mask = renderMask(text, style);
  var readability = renderReadability(mask.meta, style);
  var glow = renderGlow(text, mask.meta, style);
  var transfer = [];
  return {
    transfer: transfer,
    payload: {
      mask: packCanvas(mask.canvas, transfer),
      maskMeta: mask.meta,
      readability: packCanvas(readability, transfer),
      glow: packCanvas(glow.canvas, transfer),
      glowMeta: glow.meta
    }
  };
}

self.onmessage = function(ev) {
  var data = ev && ev.data || {};
  if (data.type !== 'render') return;
  try {
    var result = renderPayload(data.text, data.style);
    self.postMessage({
      type: 'rendered',
      id: data.id,
      key: data.key,
      text: data.text,
      styleKey: data.styleKey,
      result: result.payload
    }, result.transfer);
  } catch (err) {
    self.postMessage({
      type: 'error',
      id: data.id,
      key: data.key,
      message: err && err.message ? err.message : String(err || 'render failed')
    });
  }
};
