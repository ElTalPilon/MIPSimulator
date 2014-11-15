import java.util.Scanner;

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
			if(fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION){
				sim.loadFile(fileChooser.getSelectedFile());
				//sim.runProgram(); 
				
				/*for(int i=0; i<2; ++i){
					sim.fetch();
					sim.decode();
					sim.execute();
					sim.memory();
					sim.writeBack();
				}
				sim.imprimirEstado(); */
				
			}
			int respuestaCargar = JOptionPane.showConfirmDialog(null, "Desea cargar mas hilos?");
			if(respuestaCargar==JOptionPane.YES_OPTION){
				JOptionPane.showMessageDialog(null, "Se cargará otro hilo.");
			}else{
				terminoCargarHilos = true;
				JOptionPane.showMessageDialog(null, "NO se cargarán más hilos.");
			}
			
		}while(!terminoCargarHilos);
		//***Prueba
		//return;
		//System.out.println("Entro aca");
	}
}
