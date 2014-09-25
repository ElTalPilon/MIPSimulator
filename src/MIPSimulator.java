import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;
import java.util.concurrent.CyclicBarrier;

/**
 * Clase que simula un procesador de 1 núcleo MIPS
 *
 */
public class MIPSimulator {
	private int[] instructionMem; // Memoria de instrucciones
	private int[] dataMem;        // Memoria de datos
	private Register[] R;		  // Registros del procesador
	private CyclicBarrier clock;  // Reloj del sistema
	private int clockCycle;       // Ciclo actual de reloj
	private int PC;               // Contador del programa / Puntero de instrucciones
	
	private Register IF_ID;
	private Register IF_EX;
	private Register IF_MEM;
	private Register IF_WB;
	
	/**
	 * Construye una instancia de MIPSimulator e inicializa sus atributos.
	 */
	public MIPSimulator(){
		PC = 0;
		IF_ID = new Register();
		IF_EX = new Register();
		IF_MEM = new Register();
		IF_WB = new Register();
		clock = new CyclicBarrier(4); // El 4 no sé...
		dataMem = new int[200];
		clockCycle = 0;
		instructionMem = new int[400];
		for(int i = 0; i < instructionMem.length; i++){
			instructionMem[i] = -1;
			dataMem[i%200] = -1;
			if(i < 32){
				R[i] = new Register();
			}
		}
		R[0].lock.lock();
		R[4].set(4);
	}
	
	/**
	 * Ejecuta el programa especificado.
	 * @param program - Archivo .txt con lenguaje máquina (para MIPS) en decimal. 
	 */
	public void loadFile(File program){
		try{
			// Guarda las instrucciones del archivo en la memoria
			Scanner scanner = new Scanner(program);
			for(int i = 0; i < instructionMem.length; i++){
				if(scanner.hasNext()){
					instructionMem[i] = scanner.nextInt();
				}else{
					instructionMem[i] = -1;
				}
			}
			scanner.close();
		}catch(FileNotFoundException e){
			System.err.println("Error abriendo el archivo del programa.");
		}
		// Imprime el programa que se va a ejecutar
		printProgram();
	}
	
	public int executeInstruction(int instruction, int X, int Y, int Z){
		int nextPC = 0;
		switch(instruction){
			case 0:
				// LL RX, n(RY)
			break;
			case 1:
				// SC RX, n(RY)
			break;
			case 2:
				// JR RX
			break;
			case 3:
				// JAL n
			break;
			case 4:
				// BEQZ RX, ETIQ
			break;
			case 5:
				// BNEZ RX, ETIQ
			break;
			case 8:
				// DADDI RX, RY, #n
			break;
			case 12:
				// DMUL RX, RY, RZ
			break;
			case 14:
				// DDIV RX, RY, RZ
			break;
			case 32:
				// DADD RX, RY, RZ
			break;
			case 34:
				// DSUB RX, RY, RZ
			break;
			case 35:
				// LW RX, n(RY)
			break;
			case 43:
				// SW RX, n(RY)
			break;
			case 63:
				// FIN
			break;
			default:
			break;
		}
		
		return nextPC;
	}
	
	
	/**
	 * Despliega en consola el estado actual de:
	 *   - Ciclos de reloj ejecutados.
	 *   - Contenido en los registros.
	 *   - Contenido en la memoria de datos.
	 */
	private void printState(){
		System.out.println("Ciclos ejecutados: " + clockCycle);
		System.out.println("\n+++++CONTENIDO EN REGISTROS+++++");
		for(int i = 0; i < R.length; i++){
			System.out.println("R" + i + ": " + R[i] + " ");
		}
		System.out.println("\n+++++CONTENIDO EN MEMORIA+++++");
		for(int i = 0; i < 200; i+=10){
			for(int j = 0; j < 10; j++){
				System.out.print(dataMem[i+j] + " ");
			}
			System.out.println();
		}
	}
	
	/**
	 * Despliega el contenido de la memoria de instrucciones,
	 * es decir, el programa a ejecutar.
	 */
	private void printProgram(){
		boolean termino = false;
		System.out.println("+++++PROGRAMA A EJECUTAR+++++");
		for(int i = 0; i < 400 && termino == false; i+=4){
			for(int j = 0; j < 4; j++){
				if(instructionMem[i+j] == -1){
					termino = true;
				}
				else{
					System.out.print(instructionMem[i+j] + " ");
				}
			}
			System.out.println();
		}
	}

	public void runProgram() {
		
		//El programa se ejecuta hasta toparse con una instruccion "FIN"
		while(clockCycle == 0){//instructionMem[PC] != 63){
			System.out.println("Ciclo: " + clockCycle);
			PC = executeInstruction(instructionMem[PC], instructionMem[PC+1], instructionMem[PC+2], instructionMem[PC+3]);
			clockCycle++;
		}
		printState();
	}
}
