import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;
import java.util.concurrent.CyclicBarrier;

/**
 * Clase que simula un procesador de 1 n�cleo MIPS
 */
//esto es basura
public class MIPSimulator {
	private int[] instructionMem; // Memoria de instrucciones
	private int[] dataMem;        // Memoria de datos
	private Register[] R;		  // Registros del procesador
	private CyclicBarrier clock;  // Reloj del sistema
	private int clockCycle;       // Ciclo actual de reloj
	private int PC;               // Contador del programa / Puntero de instrucciones
	private boolean runningID;
	
	private int[] IR;
	private Register IF_ID;
	private Register IF_EX;
	private Register IF_MEM;
	private Register IF_WB;
	
	private final int DADDI = 8;
	private final int DADD = 32;
	private final int DSUB = 34;
	private final int DMUL = 12;
	private final int DDIV = 14;
	private final int LW = 35;
	private final int SW = 43;
	private final int BEQZ = 4;
	private final int BNEZ = 5;
	private final int LL = 0;
	private final int SC = 1;
	private final int JAL = 3;
	private final int JR = 2;
	private final int FIN = 63;
	
	/**
	 * Instancia de runnable que se encargar� de la etapa IF del pipeline.
	 * En esta etapa se guarda en IR la instrucci�n a la que apunta PC y,
	 * si ID no est� ocupado, se le pasa IR.
	 */
	private final Runnable IFstage = new Runnable(){
		@Override
		public void run(){
			while(runningID){
				// Guarda la instrucci�n a ejecutar en IR
				for(int i = 0; i < 4; i++){
					IR[i] = instructionMem[PC+i];
				}
				
				// Espera a que ID se desocupe con un lock o algo as�
				
				// Aumenta el PC
				PC += 4;
			}
		}
	};
	
	/**
	 * Instancia de runnable que se encargara de la etapa ID del pipeline.
	 * En esta etapa se decodifica la instrucci�n, se obtienen la informaci�n
	 * necesaria, ya sea en registros o en memoria de datos.
	 */
	private final Runnable IDstage = new Runnable(){
		@Override
		public void run(){
			while(IR[0] != FIN){
				switch(IR[0]){
					case DADDI:
					break;
					case DADD:
					break;
					case DSUB:
					break;
					case DMUL:
					break;
					case DDIV:
					break;
					case LW:
					break;
					case SW:
					break;
					case BEQZ:
						// Caso no manejado a�n
					break;
					case BNEZ:
						// Caso no manejado a�n
					break;
					case LL:
						// Caso no manejado a�n
					break;
					case SC:
						// Caso no manejado a�n
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
		IF_ID = new Register();
		IF_EX = new Register();
		IF_MEM = new Register();
		IF_WB = new Register();
		runningID = true;
		clock = new CyclicBarrier(4); // El 4 no s�...
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
	 * @param program - Archivo .txt con lenguaje m�quina (para MIPS) en decimal. 
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
