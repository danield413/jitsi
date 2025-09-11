/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Este archivo ha sido modificado para añadir una funcionalidad de
 * vista previa de cámara con efectos de video en tiempo real.
 */
package net.java.sip.communicator.impl.neomedia.video;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import javax.media.*;
import javax.media.MediaException;
import javax.swing.*;
import lombok.extern.slf4j.*;
import net.java.sip.communicator.impl.neomedia.*;
import net.java.sip.communicator.plugin.desktoputil.TransparentPanel;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.resources.*;
import org.jitsi.util.swing.*;
import org.jitsi.utils.*;

import java.awt.image.BufferedImage;

/**
 * Pestaña de configuración de dispositivos de video.
 * Modificada para incluir una vista previa manual con la opción de
 * reemplazar el video por una imagen estática personalizada.
 */
@Slf4j
public class VideoDeviceTab extends TransparentPanel
{
    private final MediaServiceImpl mediaService;
    private final JComboBox<CaptureDeviceViewModel> deviceComboBox;
    private final JComponent previewContainer;
    private final JCheckBox useImageCheckBox;
    private final JButton selectImageButton;
    private BufferedImage selectedImage;
    private ImageVideoPanel currentVideoPanel;

    /**
     * Constructor que inicializa y organiza todos los componentes de la interfaz
     */
    public VideoDeviceTab()
    {
        // Inicialización de servicios y componentes
        this.mediaService = NeomediaActivator.getMediaServiceImpl();
        ResourceManagementService res = NeomediaActivator.getResources();

        JLabel deviceLabel = new JLabel(res.getI18NString("impl.media.configform.VIDEO"));
        deviceComboBox = new JComboBox<>();
        deviceComboBox.setModel(new VideoDeviceComboBoxModel(mediaService));
        deviceComboBox.addActionListener(this::deviceComboBoxActionListener);

        // Creación y organización de los controles del usuario

        // Panel principal para los controles, usará un layout vertical (BoxLayout)
        Container deviceSelectionPanel = new TransparentPanel();
        deviceSelectionPanel.setLayout(new BoxLayout(deviceSelectionPanel, BoxLayout.Y_AXIS));

        // Primera fila de controles: Selector de cámara
        JPanel deviceRow = new TransparentPanel(new FlowLayout(FlowLayout.CENTER));
        deviceRow.add(deviceLabel);
        deviceRow.add(deviceComboBox);
        deviceSelectionPanel.add(deviceRow);

        // Segunda fila de controles: Botones de acción
        JPanel controlsRow = new TransparentPanel(new FlowLayout(FlowLayout.CENTER));

        // Botón de Activar/Desactivar Vista Previa
        JToggleButton previewButton = new JToggleButton("Activar Vista Previa");
        previewButton.addActionListener(e -> {
            if (previewButton.isSelected()) { // Si el botón está presionado
                previewButton.setText("Desactivar Vista Previa");
                createPreview();
            } else { // Si el botón no está presionado
                previewButton.setText("Activar Vista Previa");
                stopPreview();
            }
        });
        controlsRow.add(previewButton);

        // Casilla para habilitar la imagen personalizada
        useImageCheckBox = new JCheckBox("Usar imagen personalizada");
        useImageCheckBox.addActionListener(e -> updateVideoDisplay()); // Llama al método que actualiza la vista
        controlsRow.add(useImageCheckBox);

        // Botón para seleccionar el archivo de imagen
        selectImageButton = new JButton("Seleccionar Imagen");
        selectImageButton.addActionListener(this::selectImageButtonClicked); // Llama al método que abre el explorador
        controlsRow.add(selectImageButton);

        deviceSelectionPanel.add(controlsRow);

        JLabel noPreview = new JLabel(
            NeomediaActivator.getResources().getI18NString(
                "impl.media.configform.NO_PREVIEW"));
        noPreview.setHorizontalAlignment(SwingConstants.CENTER);
        noPreview.setVerticalAlignment(SwingConstants.CENTER);

        previewContainer = new VideoContainer(noPreview, false);
        previewContainer.setPreferredSize(getPreferredSize());

        // Agregar la nueva pestaña
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        add(deviceSelectionPanel, BorderLayout.NORTH);
        add(previewContainer, BorderLayout.CENTER);
    }

    /**
     * Maneja el evento de clic del botón de seleccionar imagen
     * Abre un JFileChooser para que el usuario elija un archivo (de imagen)
     */
    private void selectImageButtonClicked(ActionEvent e)
    {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setDialogTitle("Seleccionar imagen para reemplazar video");

        // Se crea un filtro para mostrar solo archivos de imagen comunes.
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String name = f.getName().toLowerCase();
                return name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".gif") ||
                    name.endsWith(".bmp");
            }

            @Override
            public String getDescription() {
                return "Archivos de imagen (*.jpg, *.png, *.gif, *.bmp)";
            }
        });

        // Muestra el diálogo y espera la selección del usuario.
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) { // Si el usuario selecciona un archivo
            try {
                File selectedFile = fileChooser.getSelectedFile();
                // Lee el archivo y lo convierte en un objeto BufferedImage.
                selectedImage = javax.imageio.ImageIO.read(selectedFile);

                if (selectedImage != null) {
                    logger.info("Imagen cargada: {} ({}x{})",
                        selectedFile.getName(),
                        selectedImage.getWidth(),
                        selectedImage.getHeight());

                    // Marca automáticamente la casilla para mostrar la imagen recién cargada.
                    useImageCheckBox.setSelected(true);
                    updateVideoDisplay();

                    JOptionPane.showMessageDialog(this,
                        "Imagen cargada correctamente: " + selectedFile.getName(),
                        "Imagen cargada",
                        JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this,
                        "No se pudo cargar la imagen seleccionada", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (IOException ex) {
                logger.error("Error cargando imagen", ex);
                JOptionPane.showMessageDialog(this,
                    "Error al cargar la imagen: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Actualiza el panel de vista previa para mostrar el video o la imagen.
     * Este método actúa como un interruptor central.
     */
    private void updateVideoDisplay()
    {
        // Solo actúa si la vista previa está activa
        if (currentVideoPanel != null) {
            // Si la casilla está marcada y hay una imagen cargada, muestra la imagen
            if (useImageCheckBox.isSelected() && selectedImage != null) {
                currentVideoPanel.showImage(selectedImage);
            } else { // Sino, muestra el video en vivo
                currentVideoPanel.showVideo();
            }
        }
    }

    /**
     * Detiene la vista previa de video y limpia
     */
    private void stopPreview()
    {
        if (currentVideoPanel != null) {
            currentVideoPanel.stop(); // Llama al método de limpieza de nuestro panel.
            currentVideoPanel = null;
        }
        previewContainer.removeAll(); // Elimina todos los componentes del contenedor
        previewContainer.revalidate();
        previewContainer.repaint();
    }

    /**
     * Se activa cuando el usuario cambia la cámara en el ComboBox
     * Reinicia la vista previa para usar el nuevo dispositivo
     */
    private void deviceComboBoxActionListener(ActionEvent e)
    {
        createPreview();
        revalidate();
        repaint();
    }

    /**
     * Inicia el proceso de creación de la vista previa de video
     */
    private void createPreview()
    {
        // Obtiene el dispositivo de captura seleccionado.
        Object selectedItem = deviceComboBox.getSelectedItem();
        CaptureDeviceInfo device = null;
        if (selectedItem instanceof CaptureDeviceViewModel)
        {
            device = ((CaptureDeviceViewModel) selectedItem).info;
        }

        try
        {
            // Llama al método que hace el trabajo pesado
            createVideoPreview(device);
        }
        catch (IOException | MediaException ex)
        {
            logger.error("Failed to create preview for device {}", device, ex);
        }
    }

    /**
     * Crea y muestra el componente de video para el dispositivo seleccionado
     */
    private void createVideoPreview(CaptureDeviceInfo device) throws IOException, MediaException
    {
        // Detiene cualquier vista previa anterior para limpiar.
        stopPreview();

        if (device == null || !deviceComboBox.isShowing())
        {
            return;
        }

        // Busca el MediaDevice correspondiente al CaptureDeviceInfo.
        for (MediaDevice mediaDevice : mediaService.getDevices(MediaType.VIDEO, MediaUseCase.ANY))
        {
            if (((MediaDeviceImpl) mediaDevice).getCaptureDeviceInfo().equals(device))
            {
                Dimension videoContainerSize = previewContainer.getSize();
                // Pide al MediaService el componente visual de Swing para el video.
                Component preview = (Component) mediaService.getVideoPreviewComponent(
                    mediaDevice,
                    videoContainerSize.width,
                    videoContainerSize.height);

                if (preview != null)
                {
                    // En lugar de añadir el video directamente, lo envolvemos en nuestro panel personalizado.
                    currentVideoPanel = new ImageVideoPanel(preview);

                    // Si la opción de usar imagen ya estaba seleccionada, la aplicamos.
                    if (useImageCheckBox.isSelected() && selectedImage != null) {
                        currentVideoPanel.showImage(selectedImage);
                    }

                    // Añadimos nuestro panel al contenedor principal.
                    previewContainer.add(currentVideoPanel);
                    previewContainer.revalidate();
                    previewContainer.repaint();
                }
                break;
            }
        }
    }

    /**
     * Clase interna: un panel que puede alternar entre mostrar un componente
     * de video en vivo y una imagen estática personalizada
     */
    private static class ImageVideoPanel extends JPanel
    {
        private final Component originalVideoComponent;
        private final JLabel imageLabel;
        private final CardLayout cardLayout;
        private BufferedImage currentImage;

        /**
         * Constructor que prepara las dos "tarjetas": una para el video y otra para la imagen
         */
        public ImageVideoPanel(Component videoComponent)
        {
            this.originalVideoComponent = videoComponent;
            this.cardLayout = new CardLayout();
            setLayout(cardLayout);
            setOpaque(true);
            setBackground(Color.BLACK); // Fondo negro para ambos paneles.

            // Tarjeta 1: Panel para el video original
            JPanel videoPanel = new JPanel(new BorderLayout());
            videoPanel.setBackground(Color.BLACK);
            videoPanel.add(videoComponent, BorderLayout.CENTER);
            add(videoPanel, "VIDEO"); // Añadimos el panel con el nombre "VIDEO"

            // Tarjeta 2: Panel para la imagen personalizada
            imageLabel = new JLabel();
            imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
            imageLabel.setVerticalAlignment(SwingConstants.CENTER);
            imageLabel.setOpaque(true);
            imageLabel.setBackground(Color.BLACK);

            JPanel imagePanel = new JPanel(new BorderLayout());
            imagePanel.setBackground(Color.BLACK);
            imagePanel.add(imageLabel, BorderLayout.CENTER);
            add(imagePanel, "IMAGE"); // Añadimos el panel con el nombre "IMAGE"

            // Por defecto, mostramos la tarjeta de video
            cardLayout.show(this, "VIDEO");
        }

        /** Muestra la "tarjeta" de la imagen. */
        public void showImage(BufferedImage image)
        {
            this.currentImage = image;
            updateImageDisplay(); // Escala la imagen antes de mostrarla
            cardLayout.show(this, "IMAGE");
        }

        /** Muestra la "tarjeta" del video */
        public void showVideo()
        {
            cardLayout.show(this, "VIDEO");
        }

        /**
         * Escala la imagen para que quepa en el panel manteniendo su proporción,
         * para evitar que se vea estirada o deformada
         */
        private void updateImageDisplay()
        {
            if (currentImage == null) return;

            Dimension panelSize = getSize();
            if (panelSize.width <= 0 || panelSize.height <= 0) {
                panelSize = new Dimension(320, 240); // Usa un tamaño por defecto si el panel aún no es visible
            }

            // Calcula el factor de escala correcto para que la imagen no se distorsione
            double scaleX = (double) panelSize.width / currentImage.getWidth();
            double scaleY = (double) panelSize.height / currentImage.getHeight();
            double scale = Math.min(scaleX, scaleY); // Usa el factor más pequeño para que quepa entera

            int scaledWidth = (int) (currentImage.getWidth() * scale);
            int scaledHeight = (int) (currentImage.getHeight() * scale);

            if (scaledWidth > 0 && scaledHeight > 0) {
                // Crea una versión escalada de la imagen y la asigna al JLabel
                Image scaledImage = currentImage.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
                imageLabel.setIcon(new ImageIcon(scaledImage));
            }
        }

        /**
         * Se sobreescribe este método para que la imagen se re-escale si el
         * tamaño de la ventana cambia
         */
        @Override
        public void doLayout() {
            super.doLayout();
            if (currentImage != null) {
                updateImageDisplay();
            }
        }

        /**
         * Método de limpieza para liberar la imagen
         */
        public void stop()
        {
            currentImage = null;
            imageLabel.setIcon(null);
        }
    }
}
