import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

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
		int quantum = 0;
		boolean quantumValido = false;
		boolean terminoCargarHilos = false;
		
		// Pide al usuario el numero de quantum con el que se trabajar el round robin
		// debera estar entre 1 y 100
		do{
			try{
				String nQuantum = JOptionPane.showInputDialog("Ingrese un numero para el quantum del MIPS:");
				if( (Integer.parseInt(nQuantum) <= 0) || (Integer.parseInt(nQuantum)  > 100)){
					JOptionPane.showMessageDialog(null, "No ingreso un quantum en el rango correcto ");
				}else{
					quantumValido = true;
					quantum = Integer.parseInt(nQuantum);
					JOptionPane.showMessageDialog(null, "Se trabajará con el quantum:  " + quantum);
				}
			}catch(NumberFormatException e){
				JOptionPane.showMessageDialog(null, "No ingreso un número válido para el quantum");
				quantumValido = false;
			}
				
		}while(!quantumValido);
		
		MIPSimulator sim = new MIPSimulator(quantum);
	
		// Se muestra el componente fileChooser para carga un hilo
		// el usuario podrá cargar tantos hilos como desee
		do{
			JFileChooser fileChooser = new JFileChooser();
			boolean sePudo = true;
			if(fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION){
				sePudo = sim.loadFile(fileChooser.getSelectedFile());
				//sim.runProgram(); 
				
				/*for(int i=0; i<2; ++i){
					sim.fetch();
					sim.decode();
					sim.execute();
					sim.memory();
					sim.writeBack();
				}*/
				sim.imprimirEstado();
			}
			if(!sePudo){
				int respuestaCargar = JOptionPane.showConfirmDialog(null, "La memoria es insuficiente para ese hilo.\n¿Desea cargar otro?");
				if(respuestaCargar==JOptionPane.NO_OPTION){
					terminoCargarHilos = true;
				}
			}
			else{
				int respuestaCargar = JOptionPane.showConfirmDialog(null, "Desea cargar otro hilo?");
				if(respuestaCargar==JOptionPane.NO_OPTION){
					terminoCargarHilos = true;
				}
			}
		}while(!terminoCargarHilos);
		//***Prueba
		//return;
		//System.out.println("Entro aca");
	}
}
