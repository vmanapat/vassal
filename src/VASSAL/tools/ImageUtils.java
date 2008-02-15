package VASSAL.tools;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ColorModel;
import java.awt.image.SampleModel;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.FileCacheImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import VASSAL.build.GameModule;
import VASSAL.configure.BooleanConfigurer;
import VASSAL.tools.imageop.Op;
import VASSAL.tools.memmap.MappedBufferedImage;

public class ImageUtils {
  // negative, because historically we've done it this way
  private static final double DEGTORAD = -Math.PI/180.0;

  private static final BufferedImage NULL_IMAGE =
    new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

  private static final GeneralFilter.Filter upscale =
    new GeneralFilter.MitchellFilter();
  private static final GeneralFilter.Filter downscale =
    new GeneralFilter.Lanczos3Filter();

  private static final ImageUtils instance = new ImageUtils();

  public static final String PREFER_MEMORY_MAPPED = "preferMemoryMapped"; //$NON-NLS-1$
  public static final String SCALER_ALGORITHM = "scalerAlgorithm"; //$NON-NLS-1$ 
  private int largeImageLoadMethod;
  private static final int MAPPED = 0;
  private static final int RAM = 1;

  private int scalingQuality;
  private static final int POOR = 0;
  private static final int MEDIUM = 1;
  private static final int GOOD = 2;

  private final Map<RenderingHints.Key,Object> defaultHints =
    new HashMap<RenderingHints.Key,Object>();

  private ImageUtils() {
    // create configurer for memory-mappedf file preference
    final BooleanConfigurer mappedPref = new BooleanConfigurer(
      PREFER_MEMORY_MAPPED,
      "Prefer memory-mapped files for large images?", //$NON-NLS-1$
      Boolean.FALSE);
//Resources.getString("GlobalOptions.smooth_scaling"), Boolean.TRUE); //$NON-NLS-1$
    mappedPref.addPropertyChangeListener(new PropertyChangeListener() {
      public void propertyChange(PropertyChangeEvent evt) {
        largeImageLoadMethod =
          Boolean.TRUE.equals(mappedPref.getValue()) ? MAPPED : RAM;
      }
    });

    GameModule.getGameModule().getPrefs().addOption(mappedPref);

    // create configurer for scaling quality
    final BooleanConfigurer scalingPref = new BooleanConfigurer(
      SCALER_ALGORITHM,
      "High-quality scaling?", //$NON-NLS-1$ 
      Boolean.TRUE);
//      Resources.getString("GlobalOptions.smooth_scaling"), Boolean.TRUE); //$NON-NLS-1$
    scalingPref.addPropertyChangeListener(new PropertyChangeListener() {
      public void propertyChange(PropertyChangeEvent evt) {
        final int newQual =
          Boolean.TRUE.equals(scalingPref.getValue()) ? GOOD : MEDIUM;
        if (newQual != scalingQuality) {
          scalingQuality = newQual;
          Op.clearCache();

          defaultHints.put(RenderingClues.KEY_EXT_INTERPOLATION,
            scalingQuality == GOOD ?
              RenderingClues.VALUE_INTERPOLATION_LANCZOS_MITCHELL :
              RenderingClues.VALUE_INTERPOLATION_BILINEAR);
        }
      }
    });

    GameModule.getGameModule().getPrefs().addOption(scalingPref);

    // set up map for creating default RenderingHints
    defaultHints.put(RenderingHints.KEY_INTERPOLATION,
                     RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    defaultHints.put(RenderingClues.KEY_EXT_INTERPOLATION,
                     RenderingClues.VALUE_INTERPOLATION_LANCZOS_MITCHELL);
    defaultHints.put(RenderingHints.KEY_ANTIALIASING,
                     RenderingHints.VALUE_ANTIALIAS_ON);
  } 

  public static RenderingHints getDefaultHints() {
    return new RenderingClues(instance.defaultHints);
  }

  public static Rectangle transform(Rectangle srect,
                                    double scale,
                                    double angle) {
    final AffineTransform t = AffineTransform.getRotateInstance(
      DEGTORAD*angle, srect.getCenterX(), srect.getCenterY());
    t.scale(scale, scale);
    return t.createTransformedShape(srect).getBounds();
  }

  public static BufferedImage transform(BufferedImage src,
                                        double scale,
                                        double angle) {
    return transform(src, scale, angle,
                     getDefaultHints(),
                     instance.scalingQuality);
  }

  public static BufferedImage transform(BufferedImage src,
                                        double scale,
                                        double angle,
                                        RenderingHints hints) {
    return transform(src, scale, angle, hints, instance.scalingQuality);
  }

/*
  public static BufferedImage transform(BufferedImage src,
                                        double scale,
                                        double angle,
                                        boolean quality) {
    return transform(src, scale, angle, getDefaultHints(),
                     quality ? GOOD : MEDIUM);
  }
*/

  public static BufferedImage transform(BufferedImage src,
                                        double scale,
                                        double angle,
                                        RenderingHints hints,
                                        int quality) {
    // bail on null source
    if (src == null) return null;

    // nothing to do, return source
    if (scale == 1.0 && angle == 0.0) {
      return src;
    }

    // return null image if scaling makes source vanish
    if (src.getWidth() * scale == 0 || src.getHeight() * scale == 0) {
      return NULL_IMAGE;
    }
  
    // use the default hints if we weren't given any
    if (hints == null) hints = getDefaultHints();
 
    if (src instanceof SVGManager.SVGBufferedImage) {
      // render SVG
      return ((SVGManager.SVGBufferedImage) src)
        .getTransformedInstance(scale, angle);
    }
    else if (scale == 1.0 && angle % 90.0 == 0.0) {
      // this is an unscaled quadrant rotation, we can do this simply
      hints.put(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
      hints.put(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);
        
      final Rectangle ubox = getBounds(src);
      final Rectangle tbox = transform(ubox, scale, angle);

      final BufferedImage trans =
        new BufferedImage(tbox.width, tbox.height,
                          BufferedImage.TYPE_INT_ARGB);

      final AffineTransform t = new AffineTransform();
      t.translate(-tbox.x, -tbox.y);
      t.rotate(DEGTORAD*angle, ubox.getCenterX(), ubox.getCenterY());
      t.scale(scale, scale);
      t.translate(ubox.x, ubox.y);

      final Graphics2D g = trans.createGraphics();
      g.setRenderingHints(hints);
      g.drawImage(src, t, null);
      g.dispose();
      return trans;
    }
    else if (hints.get(RenderingClues.KEY_EXT_INTERPOLATION) ==
                       RenderingClues.VALUE_INTERPOLATION_LANCZOS_MITCHELL) {
      // do high-quality scaling
      if (angle != 0.0) {
        final Rectangle ubox = getBounds(src);
// FIXME: this duplicates the standard scaling case
// FIXME: check whether AffineTransformOp is faster

        final Rectangle rbox = transform(ubox, 1.0, angle);

// FIXME: rotation via bilinear interpolation probably decreases quality
        final BufferedImage rot =
          new BufferedImage(rbox.width, rbox.height,
                            BufferedImage.TYPE_INT_ARGB);

        final AffineTransform tx = new AffineTransform();
        tx.translate(-rbox.x, -rbox.y);
        tx.rotate(DEGTORAD*angle, ubox.getCenterX(), ubox.getCenterY());
        tx.translate(ubox.x, ubox.y);

        final Graphics2D g = rot.createGraphics();
        g.setRenderingHints(hints);
        g.drawImage(src, tx, null);
        g.dispose();
        src = rot;
      }

/*
      // make sure that we have a TYPE_INT_ARGB before using GeneralFilter
      if (src.getType() != BufferedImage.TYPE_INT_ARGB) {
        src = toIntARGB(src);
      }
*/

      final Rectangle sbox = transform(getBounds(src), scale, 0.0);
      return GeneralFilter.zoom(sbox, src, scale > 1.0 ? upscale : downscale);
    }
    else {
      // do standard scaling
      final Rectangle ubox = getBounds(src);
      final Rectangle tbox = transform(ubox, scale, angle);

      final BufferedImage trans =
        new BufferedImage(tbox.width, tbox.height,
                          BufferedImage.TYPE_INT_ARGB);

      final AffineTransform t = new AffineTransform();
      t.translate(-tbox.x, -tbox.y);
      t.rotate(DEGTORAD*angle, ubox.getCenterX(), ubox.getCenterY());
      t.scale(scale, scale);
      t.translate(ubox.x, ubox.y);

      final Graphics2D g = trans.createGraphics();
      g.setRenderingHints(hints);
      g.drawImage(src, t, null);
      g.dispose();
      return trans;
    }
  }

  /**
   * @param im 
   * @return the boundaries of this image, where (0,0) is the
   * pseudo-center of the image
   */
  public static Rectangle getBounds(BufferedImage im) {
    return new Rectangle(-im.getWidth()/2,
                         -im.getHeight()/2,
                          im.getWidth(),
                          im.getHeight());
  }

  public static Rectangle getBounds(Dimension d) {
    return new Rectangle(-d.width / 2,
                         -d.height / 2,
                          d.width,
                          d.height);
  }

  public static Dimension getImageSize(InputStream in) throws IOException {
    final ImageInputStream stream = new MemoryCacheImageInputStream(in);
    try {
      final Iterator<ImageReader> i = ImageIO.getImageReaders(stream);
      if (!i.hasNext()) throw new IOException("Unrecognized image format");

      final ImageReader reader = i.next();
      try {
        reader.setInput(stream);
        return new Dimension(reader.getWidth(0), reader.getHeight(0));
      }
      finally {
        reader.dispose();
      }
    }
    finally {
      try {
        in.close();
      }
      catch (IOException e) {
        ErrorLog.warn(e);
      }
    }
  }

/*
  private static final int type_ordering[] = new int[]{ 
    BufferedImage.TYPE_3BYTE_BGR,
    BufferedImage.TYPE_4BYTE_ABGR,
    BufferedImage.TYPE_4BYTE_ABGR_PRE,
    BufferedImage.TYPE_BYTE_BINARY,
    BufferedImage.TYPE_BYTE_GRAY,
    BufferedImage.TYPE_BYTE_INDEXED,
    BufferedImage.TYPE_CUSTOM,
    BufferedImage.TYPE_INT_ARGB,
    BufferedImage.TYPE_INT_ARGB_PRE,
    BufferedImage.TYPE_INT_BGR,
    BufferedImage.TYPE_INT_RGB,
    BufferedImage.TYPE_USHORT_555_RGB,
    BufferedImage.TYPE_USHORT_565_RGB,
    BufferedImage.TYPE_USHORT_GRAY,
  };
*/

  public static Image getImage(InputStream in) throws IOException {
    return getSmallImage(in);
  }

  public static Image getSmallImage(InputStream in) throws IOException {
    final BufferedImage img =
      ImageIO.read(new MemoryCacheImageInputStream(in));
     
    return img.getType() != BufferedImage.TYPE_INT_ARGB
      ? ImageUtils.toIntARGBSmall(img) : img;
  }

  public static Image getLargeImage(String name) throws IOException {
/*
    ImageInputStream stream = new MemoryCacheImageInputStream(
      GameModule.getGameModule().getDataArchive().getImageInputStream(name));
    try {
      // find the best reader
      loop:
      for (ImageReader r : iterate(ImageIO.getImageReaders(stream))) {
        r.setInput(stream);
        for (ImageTypeSpecifier t : iterate(r.getImageTypes(0))) {
          String ts = null;
          switch (t.getBufferedImageType()) {
          case BufferedImage.TYPE_3BYTE_BGR:
            ts = "TYPE_3BYTE_BGR"; break;
          case BufferedImage.TYPE_4BYTE_ABGR:
            ts = "TYPE_4BYTE_ABGR"; break;
          case BufferedImage.TYPE_4BYTE_ABGR_PRE:
            ts = "TYPE_4BYTE_ABGR_PRE"; break;
          case BufferedImage.TYPE_BYTE_BINARY:
            ts = "TYPE_BYTE_BINARY"; break;
          case BufferedImage.TYPE_BYTE_GRAY:
            ts = "TYPE_BYTE_GRAY"; break;
          case BufferedImage.TYPE_BYTE_INDEXED:
            ts = "TYPE_BYTE_INDEXED"; break;
          case BufferedImage.TYPE_CUSTOM:
            ts = "TYPE_CUSTOM"; break;
          case BufferedImage.TYPE_INT_ARGB:
            ts = "TYPE_INT_ARGB"; break;
          case BufferedImage.TYPE_INT_ARGB_PRE:
            ts = "TYPE_INT_ARGB_PRE"; break;
          case BufferedImage.TYPE_INT_BGR:
            ts = "TYPE_INT_BGR"; break;
          case BufferedImage.TYPE_INT_RGB:
            ts = "TYPE_INT_RGB"; break;
          case BufferedImage.TYPE_USHORT_555_RGB:
            ts = "TYPE_USHORT_555_RGB"; break;
          case BufferedImage.TYPE_USHORT_565_RGB:
            ts = "TYPE_USHORT_565_RGB"; break;
          case BufferedImage.TYPE_USHORT_GRAY:
            ts = "TYPE_USHORT_GRAY"; break;
          default:
            assert false;
          }  
          System.out.println(ts);

          switch (t.getBufferedImageType()) {
          case BufferedImage.TYPE_INT_ARGB:
            reader = r;
            type = t;
            break loop;
          case BufferedImage.TYPE_3BYTE_BGR:
          case BufferedImage.TYPE_4BYTE_ABGR:
          case BufferedImage.TYPE_4BYTE_ABGR_PRE:
          case BufferedImage.TYPE_BYTE_BINARY:
          case BufferedImage.TYPE_BYTE_GRAY:
          case BufferedImage.TYPE_BYTE_INDEXED:
          case BufferedImage.TYPE_CUSTOM:
          case BufferedImage.TYPE_INT_ARGB_PRE:
          case BufferedImage.TYPE_INT_BGR:
          case BufferedImage.TYPE_INT_RGB:
          case BufferedImage.TYPE_USHORT_555_RGB:
          case BufferedImage.TYPE_USHORT_565_RGB:
          case BufferedImage.TYPE_USHORT_GRAY:
            reader = r;
            type = t;
            break;
          default:
            assert false;
          }
        }
      }
    }
    finally {
      try {
        stream.close();
      }
      catch (IOException e) {
        e.printStackTrace();
      }
    }
 
    if (reader == null || type == null)
      throw new IOException("Unrecognized image format");
*/

    BufferedImage img = null;

    // first try preferred storage type 
    int method = instance.largeImageLoadMethod;
    done:
    for (int tries = 0; tries < 2; ++tries, ++method) {
      final ImageInputStream stream = new FileCacheImageInputStream(
        GameModule.getGameModule().getDataArchive().getImageInputStream(name),
        TempFileManager.getInstance().getSessionRoot());
      try {
        final Iterator<ImageReader> i = ImageIO.getImageReaders(stream);
        if (!i.hasNext()) throw new IOException("Unrecognized image format");

        final ImageReader reader = i.next();
        try {
          reader.setInput(stream);
  
          final int w = reader.getWidth(0);
          final int h = reader.getHeight(0);
  
          switch (method % 2) {
          case MAPPED:
            try {
              final ImageTypeSpecifier type = reader.getImageTypes(0).next();
  
              // get our ColorModel and SampleModel
              final ColorModel cm = type.getColorModel();
              final SampleModel sm =
                type.getSampleModel().createCompatibleSampleModel(w,h);
      
              img = MappedBufferedImage.createMemoryMappedImage(cm, sm);
      
              final ImageReadParam param = reader.getDefaultReadParam();
              param.setDestination(img);
              reader.read(0, param);
              break done;
            }
            catch (IOException e) {
              // ignore, we throw an OutOfMemoryError at the bottom 
            }
            break;
          case RAM:
            try {
              img = reader.read(0);
              break done;
            }
            catch (OutOfMemoryError e) {
              // ignore, we throw an OutOfMemoryError at the bottom 
            }
            break;
          default:
            assert false;
          }  
        }
        finally {
          reader.dispose();
        }
      }
      finally {
        try {
          stream.close();
        }
        catch (IOException e) {
          ErrorLog.warn(e);
        }
      }
    }

    if (img == null) throw new OutOfMemoryError();
  
    return img.getType() != BufferedImage.TYPE_INT_ARGB
      ? ImageUtils.toIntARGBLarge(img) : img;
  }

  public static boolean isLargeImage(int w, int h) {
    return 4*w*h > 1024*1024;
  }

  private static BufferedImage rowByRowCopy(BufferedImage src,
                                            BufferedImage dst) {
    final int h = src.getHeight();
    final int[] row = new int[src.getWidth()];
    for (int y = 0; y < h; ++y) {
      src.getRGB(0, y, row.length, 1, row, 0, row.length);
      dst.setRGB(0, y, row.length, 1, row, 0, row.length);
    }
    return dst;
  }

  private static BufferedImage colorConvertCopy(BufferedImage src,
                                                BufferedImage dst) {
    final ColorConvertOp op = new ColorConvertOp(
      src.getColorModel().getColorSpace(),
      dst.getColorModel().getColorSpace(), null);

    op.filter(src, dst);
    return dst;
  }

  public static BufferedImage toIntARGBLarge(BufferedImage src) {
    final int w = src.getWidth();
    final int h = src.getHeight();

    int method = instance.largeImageLoadMethod;
    for (int tries = 0; tries < 2; ++tries, ++method) {
      switch (method % 2) {
      case MAPPED:
        try {
          return rowByRowCopy(src,
            MappedBufferedImage.createIntARGBMemoryMappedImage(w, h));
        }
        catch (IOException e) {
          // ignore, we throw an OutOfMemoryError at bottom
        }
        break;
      case RAM:
        try {
          final BufferedImage dst =
            new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

          return src instanceof MappedBufferedImage
            ? rowByRowCopy(src, dst) : colorConvertCopy(src, dst);
        }
        catch (OutOfMemoryError e) {
          // ignore, we throw an OutOfMemoryError at bottom
        }
        break;
      default:
        assert false;
      }  
    }

    throw new OutOfMemoryError();
  }

  public static BufferedImage toIntARGBSmall(BufferedImage src) {
    final BufferedImage dst = new BufferedImage(
      src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
    return colorConvertCopy(src, dst);
  }

  public static BufferedImage toIntARGB(BufferedImage src) {
    return toIntARGBSmall(src);
  }

  /**
   * Create a memory-mapped image from an existing image.
   *
   * @param src the source image
   * @return a memory-mapped copy
   * @throws IOException if the memory-mapped image cannot be created
   */
  public static BufferedImage toMemoryMapped(Image src)
      throws IOException {
    final BufferedImage dst =
      MappedBufferedImage.createIntARGBMemoryMappedImage(
        src.getWidth(null), src.getHeight(null));

    final Graphics2D g = dst.createGraphics();
    g.drawImage(src, 0, 0, null);
    g.dispose();
    return dst;
  }
}