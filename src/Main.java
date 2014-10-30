import javax.swing.JFileChooser;

/**
 * Simulador MIPS
 * 
 * @author Alexis Alvarado   - A90280
 * @author Jose Pablo Flores - B02409
 * @author Roy Ramos         - A85185
 */
public class Main {
	public static void main(String[] args) {
		// prueba repo
		MIPSimulator sim = new MIPSimulator();

		JFileChooser fileChooser = new JFileChooser();
		if(fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION){
			sim.loadFile(fileChooser.getSelectedFile());
			sim.runProgram();
			
			
			//sim.printState();
			
			
		}
	}
}
