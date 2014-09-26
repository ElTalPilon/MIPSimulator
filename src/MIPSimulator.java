import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;
import java.util.concurrent.CyclicBarrier;

/**
 * Clase que simula un procesador de 1 núcleo MIPS
 */
public class MIPSimulator {
	private int[] instructionMem; // Memoria de instrucciones
	private int[] dataMem;        // Memoria de datos
	private Register[] R;		  // Registros del procesador
	private CyclicBarrier clock;  // Reloj del sistema
	private int clockCycle;       // Ciclo actual de reloj
	private int PC;               // Contador del programa / Puntero de instrucciones
	private boolean runningID;
	
	private int[] IR;
	private int[] IF_ID;
	private int[] ID_EX;
	private int[] EX_M;
	private int[] M_WB;
	
	private final int DADDI = 8;
	private final int DADD = 32;
	private final int DSUB = 34;
	private final int DMUL = 12;
	private final int DDIV = 14;
	private final int LW = 35;
	private final int SW = 43;
	private final int JAL = 3;
	private final int JR = 2;
	private final int FIN = 63;
	
	/**
	 * Instancia de runnable que se encargará de la etapa IF del pipeline.
	 * En esta etapa se guarda en IR la instrucción a la que apunta PC y,
	 * si ID no está ocupado, se le pasa IR.
	 */
	private final Runnable IFstage = new Runnable(){
		@Override
		public void run(){
			while(runningID){
				// Guarda la instrucción a ejecutar en IR
				for(int i = 0; i < 4; i++){
					IR[i] = instructionMem[PC+i];
				}
				
				// Espera a que ID se desocupe con un lock o algo así
				
				// Aumenta el PC
				PC += 4;
			}
		}
	};
	
	/**
	 * Instancia de runnable que se encargara de la etapa ID del pipeline.
	 * En esta etapa se decodifica la instrucción, se obtienen la información
	 * necesaria, ya sea en registros o en memoria de datos.
	 */
	private final Runnable IDstage = new Runnable(){
		@Override
		public void run(){
			while(IR[0] != FIN){
				switch(IR[0]){
					case DADDI:
						IR[1] = R[IR[1]].get(); // Y
						IR[2] = R[IR[2]].get(); // X
						                 // IR[3]  n
					break;
					case DADD:
						IR[1] = R[IR[1]].get(); // Y
						                  // IR[2] Z
						IR[3] = R[IR[3]].get(); // X
					break;
					case DSUB:
						IR[1] = R[IR[1]].get();
						// En IR[2] se mantiene el # de registro que se necesita
						IR[3] = R[IR[3]].get();
					break;
					case DMUL:
						IR[1] = R[IR[1]].get();
						// En IR[2] se mantiene el # de registro que se necesita
						IR[3] = R[IR[3]].get();
					break;
					case DDIV:
						IR[1] = R[IR[1]].get();
						// En IR[2] se mantiene el # de registro que se necesita
						IR[3] = R[IR[3]].get();
					break;
					case LW:
					break;
					case SW:
					break;
					case JAL:
					break;
					case JR:
					break;
				}
			}
			
		}
	};
	
	private final Runnable EXstage = new Runnable(){
		@Override
		public void run(){}
	};
	
	private final Runnable Mstage = new Runnable(){
		@Override
		public void run(){}
	};
	
	private final Runnable WBstage = new Runnable(){
		@Override
		public void run(){}
	};
	
	/**
	 * Construye una instancia de MIPSimulator e inicializa sus atributos.
	 */
	public MIPSimulator(){
		PC = 0;
		IR = new int[4];
		IF_ID = new int[3];
		ID_EX = new int[3];
		EX_M = new int[2];
		M_WB = new int[1];
		runningID = true;
		clock = new CyclicBarrier(4); // El 4 no sé...
		dataMem = new int[200];
		clockCycle = 0;
		instructionMem = new int[400];
		R = new Register[32];
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
			Thread IF = new Thread(IFstage);
			Thread ID = new Thread(IDstage);
			Thread EX = new Thread(EXstage);
			Thread M  = new Thread(Mstage);
			Thread WB = new Thread(WBstage);
			
			IF.run();
;			clockCycle++;
		}
		printState();
	}
}
