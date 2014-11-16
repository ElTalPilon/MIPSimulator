import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
//import java.util.concurrent.locks.Lock;
//import java.util.concurrent.locks.ReentrantLock;

/**
 * Clase que simula un procesador de 1 núcleo MIPS  
 */
//
public class MIPSimulator {
	private final int tamMemInstrucciones = 768;//768 / 4 = 192 instrucciones / 4 = 48 bloques
	private final int tamMemDatos = 832; 		//se asume que cada entero es de 4 bytes
	private final int numBloquesCache = 8;		//el cache tiene 8 bloques
	private final int DADDI = 8;
	private final int DADD 	= 32;
	private final int DSUB 	= 34;
	private final int DMUL 	= 12;
	private final int DDIV 	= 14;
	private final int LW 	= 35;
	private final int SW 	= 43;
	private final int FIN 	= 63;
	private final int LL 	= 50;
	private final int SC	= 51;
	
	private int linkRegister;
	private int[] R;		  		// Registros del procesador
	private int[] instructionMem;	// Memoria de instrucciones
	private int[] dataMem;        	// Memoria de datos
	private Bloque[] cache;			//cache del mips, formada de 8 bloques de 16 enteros c/u
	
	private static int clock;  // Reloj del sistema
	private int PC;               // Contador del programa / Puntero de instrucciones
	int quantum;  // El quatum para implementar el round round robin
	
	
	//en la ultima posicion se pasa el operation code
	private int[] IR;
	private int[] IF_ID = {-1,-1,-1,-1};
	private int[] ID_EX = {-1,-1,-1, -1};
	private int[] EX_MEM = {-1,-1, -1};
	private int[] MEM_WB = {-1,-1, -1};
	
	private int opCode = -1;
	
	//semaforos por cada etapa
	private static Semaphore semIf = new Semaphore(1);
	private static Semaphore semId = new Semaphore(1);
	private static Semaphore semEx = new Semaphore(1);
	private static Semaphore semMem = new Semaphore(1);
	private static Semaphore semR = new Semaphore(1);
	
	// Semaforo para controlar cada ciclo del reloj
	static CyclicBarrier barrier = new CyclicBarrier(6);
	
	//booleanos para saber si etapas estan vivas
	private boolean ifAlive;
	private boolean idAlive;
	private boolean exAlive;
	private boolean memAlive;
	private boolean wbAlive;
	
	//Constructor de la clase
	public MIPSimulator(int quantum){
		this.quantum = quantum;
		
		//se inicializan los registros
		R = new int[32];
		for(int i=0; i<32; ++i){
			R[i] = 0;
		}
		//se inicializa la memoria de instrucciones
		instructionMem = new int[tamMemInstrucciones];
		for(int i=0; i<tamMemInstrucciones; ++i){
			instructionMem[i] = -1;
		}
		//se inicializa la memoria de datos
		dataMem = new int[tamMemDatos];
		for(int i=0; i<tamMemDatos; ++i){
			dataMem[i] = 1;	//SE INICIALIZA EN 1 PARA SIMULAR QUE LOS CANDADOS
							//FUERON INICIADOS POR EL HILO PRINCIPAL
		}
		//se inicializa la cache
		cache = new Bloque[numBloquesCache];
		for(int i=0; i<numBloquesCache; ++i){
			cache[i] = new Bloque();
		}
		
		clock = 0;
		PC = 0;
		
		
		IR = new int[4];
		
		linkRegister = -1;
		
		//todos empezarian con vida
		idAlive = true;
		ifAlive = true;
		exAlive = true;
		memAlive = true;
		wbAlive = true;
	}
	
	/**
	 * Instancia de runnable que se encargará de la etapa IF del pipeline.
	 * En esta etapa se guarda en IR la instrucción a la que apunta PC y,
	 * si ID no está ocupado, se le pasa IR.
	 */
	private final Runnable IFstage = new Runnable(){
		@Override
		public void run(){
			while(ifAlive == true){
				
				// Obtiene la instruccion del verctor de
				// de instrucciones
				for(int i = 0; i < 4; ++i){
					IR[i] = instructionMem[PC+i];
				}
				
				
				// Cuando IF termina, hace un await para actualizar
				//  ademas se sale del ciclo
				if(IR[0] == FIN){
					ifAlive = false;
				}
				
				// Guarda la instrucción a ejecutar en IR
				// hasta que ID este desocupado se ejecuta
				try {
					semIf.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				for(int i = 0; i < 4; ++i){
					IF_ID[i] = IR[i];
				}
				
				//*****
				System.out.println("ENTRO: IF_ID: " + IF_ID[0] +
						IF_ID[1] + IF_ID[2] + IF_ID[3]);
				// Aumenta el PC
				PC += 4;
				// Espera a que ID se desocupe con un lock o algo así
				semIf.release(1);
				
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					e.printStackTrace();
				}
			}//fin de while(alive)
			
			// Muere y actualiza la barrera para controlar
			// el ciclo del reloj
			while (wbAlive) {
                try {
                    barrier.await();
                    barrier.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (BrokenBarrierException e) {
                	e.printStackTrace();
                }
            }
			
		}//fin de metodo run
	};//fin de metodo IFstage
	
	/**
	 * Instancia de runnable que se encargara de la etapa ID del pipeline.
	 * En esta etapa se decodifica la instrucción, se obtienen la información
	 * necesaria, ya sea en registros o en memoria de datos.
	 */
	private final Runnable IDstage = new Runnable(){
		@Override
		public void run(){
			while(idAlive == true){
				if(IF_ID[0] == FIN){
					idAlive = false;
				}
				
				//Si es la primera instruccion no hace nada
				//y desbloquea la etapa hacia atras
				// aunque no haga nada tiene que actualizar el barrier
				// para que se actualice el ciclo del reloj
				if(IF_ID[0] == -1){
					semIf.release();
					try {
						barrier.await();
						barrier.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (BrokenBarrierException e) {
						e.printStackTrace();
					}
					continue;
					
				}
				
				
				try {
					semId.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				try {
					semR.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				switch(IF_ID[0]){//contiene el código de instruccion
				case DADDI:
					ID_EX[0] = R[IF_ID[1]]; // RY
					ID_EX[1] = IF_ID[2];          // X
					ID_EX[2] = IF_ID[3];          // n
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case DADD:
					ID_EX[0] = R[IF_ID[1]]; // Reg Operando1
					ID_EX[1] = R[IF_ID[2]]; // Reg Operando2
					ID_EX[2] = IF_ID[3];          // Reg Destino
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case DSUB:
					ID_EX[0] = R[IF_ID[1]]; // Reg Operando1
					ID_EX[1] = R[IF_ID[2]]; // Reg Operando2
					ID_EX[2] = IF_ID[3];          // Reg Destino
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case DMUL:
					ID_EX[0] = R[IF_ID[1]]; // Reg Operando1
					ID_EX[1] = R[IF_ID[2]]; // Reg Operando2
					ID_EX[2] = IF_ID[3];          // Reg Operando3
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case DDIV:
					ID_EX[0] = R[IF_ID[1]];			// Reg Operando1
					ID_EX[1] = R[IF_ID[2]];			// Reg Operando2
					ID_EX[2] = IF_ID[3];			// Reg Operando3
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case LW:
					ID_EX[0] = IF_ID[3]; 			// valor inmediato
					ID_EX[1] = R[IF_ID[1]];  		// Origen
					ID_EX[2] = IF_ID[2];          	// Destino
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case SW:
					ID_EX[0] = IF_ID[3]; 			// valor inmediato
					ID_EX[1] = R[IF_ID[2]]; 	// Origen
					ID_EX[2] = R[IF_ID[1]];	// Destino
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case LL:
					
				break;
				case SC:
					
				break;
				
				case FIN:
					ID_EX[0] = 0; 			// valor inmediato
					ID_EX[1] = 0; 	// Origen
					ID_EX[2] = 0;	// Destino
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				
			  }//fin del switch
				semId.release();
				semIf.release();
				semR.release();
				//*****
				System.out.println("ENTRO: ID_EX: " + ID_EX[0] +
						ID_EX[1] + ID_EX[2] + ID_EX[3]);
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					e.printStackTrace();
				}
				
			}//fin del while alive
			
			// Actualiza el ciclo del reloj cuando muere
			while (wbAlive) {
                try {
                    barrier.await();
                    barrier.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (BrokenBarrierException e) {
                	e.printStackTrace();
                }
            }
			
		}//fin del metodo run
	};//fin del metodo IDstage
	
	/** 
	 * En esta etapa se escribe en EX_M
	 * debe verificar que EX_M no este bloqueado
	 */
	private final Runnable EXstage = new Runnable(){
		@Override
		public void run(){
			
			while(exAlive == true){
				
				if(ID_EX[3] == FIN){
					exAlive = false;
				}
				
				//Si es la primera instruccion no hace nada
				//y desbloquea la etapa hacia atras
				// actualiza el ciclo del reloj
				if(ID_EX[3] == -1){
					semId.release();	
					try {
						barrier.await();
						barrier.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (BrokenBarrierException e) {
						e.printStackTrace();
					}
					
					continue;
				} // fin if cuando esta comenzando las etapas
				
				// Si Mem Stage lo desbloqueo ejecuta las operaciones
				// para guardar resultados en la ALU
				try {
					semEx.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				switch(ID_EX[3]){
					case DADDI:
						EX_MEM[0] = ID_EX[0]+ID_EX[2];
						EX_MEM[1] = ID_EX[1]; //como escribe en registro, el campo de memoria va vacio
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case DADD:
						EX_MEM[0] = ID_EX[0] + ID_EX[1];
						EX_MEM[1] = ID_EX[2];
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case DSUB:
						EX_MEM[0] = ID_EX[0] - ID_EX[1];
						EX_MEM[1] = ID_EX[2];
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case DMUL:
						EX_MEM[0] = ID_EX[0] * ID_EX[1];
						EX_MEM[1] = ID_EX[2];
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case DDIV:
						EX_MEM[0] = ID_EX[0] / ID_EX[1];
						EX_MEM[1] = ID_EX[2];
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case LW:
						EX_MEM[0] = ID_EX[0]+ID_EX[1];	//Resultado de memoria del ALU
						EX_MEM[1] = ID_EX[2];			//Destino
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case SW:
						EX_MEM[0] = ID_EX[0]+ID_EX[2];	//Resultado de memoria del ALU
						EX_MEM[1] = ID_EX[1];			//Destino
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case FIN:
						EX_MEM[0] = 0;	//Resultado de memoria del ALU
						EX_MEM[1] = 0;			//Destino
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
				}//fin del switch
				semEx.release();
				semId.release();
				//***** PRUEBA
				System.out.println("ENTRO EX_MEM: " + EX_MEM[0] +
						EX_MEM[1] + EX_MEM[2] );
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					e.printStackTrace();
				}
				
			}//fin del while alive
			
			// Muere y actualiza el reloj
			while (wbAlive) {
                try {
                    barrier.await();
                    barrier.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (BrokenBarrierException e) {
                	e.printStackTrace();
                }
            }
			
		}//fin del metodo run
	};//fin del metodo EXstage
	
	//En esta etapa se escribe en MEM_WB
	//
	private final Runnable MEMstage = new Runnable(){
		@Override
		public void run(){
			
			while(memAlive == true){
				
				// Si el codigo de operacion es 63
				// la etapa tiene que termiar-morir
				if(EX_MEM[2] == FIN){
					memAlive = false;
				}
				
				//Si es la primera instruccion no hace nada
				// libera a EX y actualiza el reloj
				if(EX_MEM[2] == -1){
					semEx.release();
					try {
						barrier.await();
						barrier.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (BrokenBarrierException e) {
						e.printStackTrace();
					}
					continue;
				}
				
				try {
					semMem.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				boolean direccionValida;
				int bloqueMem, bloqueCache;
				switch(EX_MEM[2]){
					case LW:
						/*codigo viejo, sin implementacion de cache
						MEM_WB[0] = dataMem[EX_MEM[0]]; //
						MEM_WB[1] = EX_MEM[1];			//
						MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion */
						
						//primero hay que verificar que la direccion que se tratará de leer sea valida
						direccionValida = verificarDirMem(EX_MEM[0]);
						if(direccionValida){//si diera false, sería bueno agregar un manejo de excepcion
							//si fuera válida, hay que verificar que haya un hit de memoria en cache.
							//si diera fallo, hay que traer el bloque desde memoria, si estuviera se toma directo de la cache
							bloqueMem = calcularBloqueMemoria(EX_MEM[0]);
							bloqueCache = calcularBloqueCache(EX_MEM[0]);
							if(!hitMemoria(EX_MEM[0])){
								//si hubiera fallo de memoria y el bloque en cache correspondiente está modificado,
								//primero hay que guardarlo a memoria. para esto primero calculamos el bloque en memoria
								//del dato que se quiere cargar. es aqui cuando se usa la estrategia WRITE ALLOCATE
								if(cache[bloqueCache].getEtiqueta() == bloqueMem && cache[bloqueCache].getEtiqueta() == 'm'){
									cacheStore(bloqueCache);
								}
								//en este punto se hace se carga la cache, pues no hubo hit de memoria
								//y si el bloque estaba modificado ya se guardó antes a memoria
								//es aqui cuando se usa la estrategia de WRITE BACK
								cacheLoad(EX_MEM[0]);
							}
							//en este punto sí hubo hit de memoria, por lo que se carga el dato desde la cache
							int indice = ((EX_MEM[0]+768) / 16 ) % 4;
							MEM_WB[0] = cache[bloqueCache].getValor(indice);
							MEM_WB[1] = EX_MEM[1];			//
							MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion */			
						}//fin de if direccionValida
					break;
					
					case SW:
						/*codigo viejo, sin implementacion de cache
						dataMem[EX_MEM[0]] = EX_MEM[1]; //se hace el store en memoria
						MEM_WB[0] = EX_MEM[0];			//no hace nada pero tiene que pasar el valor
						MEM_WB[1] = EX_MEM[1];			//no hace nada pero tiene que pasar el valor
						MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion*/
						
						//nuevamente, lo primero es verificar que la referencia a memoria sea valida
						direccionValida = verificarDirMem(EX_MEM[0]);
						if(direccionValida){
							//si fuera valida, se verifica si el bloque de memoria está en cache
								bloqueMem = calcularBloqueMemoria(EX_MEM[0]);
								bloqueCache = calcularBloqueCache(EX_MEM[0]);
								if(!hitMemoria(EX_MEM[0])){
									//si no hay hit de memoria hay que cargar el bloque a cache, pero si en el bloque de cache
									//donde vamos a escribir está modificado, primero hay que escribir el bloque actual a memoria:
									if(cache[bloqueCache].getEtiqueta() == bloqueMem && cache[bloqueCache].getEtiqueta() == 'm'){
										cacheStore(bloqueCache);
									}
									//si no estuviera modificado, entonces ya podemos escribir el bloque de cache a memoria
									cacheLoad(EX_MEM[0]);
								}
								//con el bloque ya en cache, debemos escribir en la posicion correcta del bloque
								int offset = (EX_MEM[0] / 4) % 4; //se calcula el desplazamiento en el bloque
								cache[bloqueCache].setBloquePos(offset, EX_MEM[1]); //el valor a guardar esta en EX_MEM[1]
								cache[bloqueCache].setEstado('m'); //el estado debe pasar a modificado
								
								//pasa los valores a MEM_WB
								MEM_WB[0] = EX_MEM[0];			//no hace nada pero tiene que pasar el valor
								MEM_WB[1] = EX_MEM[1];			//no hace nada pero tiene que pasar el valor
								MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion*/
						}
					break;
					
					// aqui se maneja el caso de que sea otra operacion
					// resuelta en EX o sea codigo de operacion FIN
					default:
						MEM_WB[0] = EX_MEM[0];
						MEM_WB[1] = EX_MEM[1];
						MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion
				}//fin del switch
				
				semMem.release();
				semEx.release();
				
				//*****PRUEBA
				System.out.println("ENTRO MEM_WB: " + MEM_WB[0] +
						MEM_WB[1] + MEM_WB[2] );
				
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					e.printStackTrace();
				}
				
				
			}//fin del while
			
			// Termina y actualiza el reloj
			while (wbAlive) {
                try {
                    barrier.await();
                    barrier.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (BrokenBarrierException e) {
                	e.printStackTrace();
                }
            }
			
		}//fin del metodo run
	};//fin del metodo MEMstage
	
	private final Runnable WBstage = new Runnable(){
		@Override
		public void run(){
			
			while(wbAlive == true){
				
				if(MEM_WB[2] == FIN){
					wbAlive = false;
				}
				
				//Si es la primera instruccion no hace nada
				// libera registros y Mem Stage y actualiza el reloj
				if(MEM_WB[2] == -1){
					semMem.release();
					semR.release();
					try {
						barrier.await();
						barrier.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (BrokenBarrierException e) {
						e.printStackTrace();
					}
					continue;
				}
				
				switch(MEM_WB[2]){
					case DADDI:
						R[MEM_WB[1]] = MEM_WB[0];
					break;
					case DADD:
						R[MEM_WB[1]] = MEM_WB[0];
					break;
					case DSUB:
						R[MEM_WB[1]] = MEM_WB[0];
					break;
					case DMUL:
						R[MEM_WB[1]] = MEM_WB[0];
					break;
					case DDIV:
						R[MEM_WB[1]] = MEM_WB[0];
					break;
					case LW:
						R[MEM_WB[1]] = MEM_WB[0];
					break;
					//en RW no hace nada en la etapa de writeback
				}//fin del switch
				
				semMem.release(1);
				semR.release();
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					e.printStackTrace();
				}
				
			}// fin del while	
			
			
			// Muere y aumenta el ciclo del reloj
			try {
				barrier.await();
				barrier.await();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (BrokenBarrierException e) {
				e.printStackTrace();
			}
			
			
		}//fin del metodo run
	};//fin del metodo WBstage
	
	/**
	 * Ejecuta el programa especificado.
	 * @param program - Archivo .txt con lenguaje máquina (para MIPS) en decimal. 
	 */
	public void loadFile(File program){
		try{
			// Guarda las instrucciones del archivo en la memoria
			// TODO: tiene que cargar más de un hilo
			
			int primerCampoVacio = 0;
			while(instructionMem[primerCampoVacio] != -1){
				primerCampoVacio++;
			}
			
			Scanner scanner = new Scanner(program);
			
			for(int i = primerCampoVacio; i < instructionMem.length; i++){
				if(scanner.hasNext()){
					instructionMem[i] = scanner.nextInt();
				}
			}
			if(scanner.hasNext()){
				System.out.println("Error: La memoria es insuficiente para guardar ese hilo");
				for(int i = primerCampoVacio; i < instructionMem.length; i++){
					instructionMem[i] = -1;
				}
			}
			
			scanner.close();
		}catch(FileNotFoundException e){
			System.err.println("Error abriendo el archivo del programa.");
		}
		//printProgram();
	}	
		
		/*System.out.print("\nPC: " + PC + "\n");
		
		System.out.print("IR: ");
		for(int i=0; i<4; ++i){
			System.out.print(IR[i] + ", ");
		}
		System.out.println();
		
		System.out.println("\nIF_ID: " + IF_ID[0] + ", " + IF_ID[1] + ", " + IF_ID[2] + ", " + IF_ID[3]);
		System.out.println("ID_EX: " + ID_EX[0] + ", " + ID_EX[1] + ", " + ID_EX[2]);
		System.out.println("EX_MEM: " + EX_MEM[0] + ", " + EX_MEM[1]);
		System.out.println("MEM_WB: " + MEM_WB[0] + ", " + MEM_WB[1]);*/
	
	
	/**
	 * Despliega el contenido de la memoria de instrucciones,
	 * es decir, el programa a ejecutar.
	 */
	public void printProgram(){
		boolean termino = false;
		System.out.println("+++++PROGRAMA A EJECUTAR+++++");
		for(int i = 0; i < instructionMem.length*4 && termino == false; i+=4){
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

	public void iniciarSemaforos(){
		semId.drainPermits();
		semIf.drainPermits();
		semMem.drainPermits();
		semEx.drainPermits();
	}
	
	public void runProgram() {
		
		iniciarSemaforos();
		Thread IF = new Thread(IFstage);
		Thread ID = new Thread(IDstage);
		Thread EX = new Thread(EXstage);
		Thread M  = new Thread(MEMstage);
		Thread WB = new Thread(WBstage);
		IF.start();
		ID.start();
		EX.start();
		M.start();
		WB.start();
		
		while(algunaEtapaViva() == true){
			try {
				
				// esperar que se ejecuten todos y entrar
				barrier.await();
				//vuelve a iniciar los semaforos, es decir bloquea los intermedios
				iniciarSemaforos();
				
				// bloquea los registros
				try{// bloquea los registros
					semR.acquire();
	            } catch (InterruptedException e) {
	                 e.printStackTrace();
	            }

			    clock++;
			    // TODO: AQUI VA DONDE COMPARA CUANTOS CLOCKS LLEVA EL HILO CON EL QUAUNTUM Y LLAMA AL CAMBIO DE CONTEXTO
			    
				barrier.await();
				//printState();
				
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (BrokenBarrierException e) {
				e.printStackTrace();
			}
		}
		//printState();
		System.out.println("--- Ciclos de reloj: " + clock + " ---");
	}
	
	
	private boolean algunaEtapaViva(){
		//mientras alguna viva el principal seguira ejecutandose
		boolean algunaVive = false;
		if(ifAlive || idAlive || exAlive || memAlive || wbAlive){
			algunaVive = true;
		}
		return algunaVive;
	}
	
	//calcula el bloque de memoria en el que se encuentra una direccion de memoria específica,
	//ej: la direccion de memoria 0768 está en el bloque 48 de memoria, pues 768 / 16 = 48
	//	  la direccion de memoria 4092 está en el bloque 256 de memoria, pues 4092/16 = 255
	public int calcularBloqueMemoria(int dirMemoria){
		int posBloqueMem = dirMemoria / 16;
		return posBloqueMem;
	}
	
	//calcula el bloque de cache donde se debe almacenar un bloque de memoria específico,
	//usando la estrategia de MAPEO DIRECTO
	//recibe: la direccion de memoria, a la cual se le calculará su bloque de memoria
	//ej: la direccion 3324 está en la direccion [831] del vector de memoria de datos,
	//    la direccion de meroria [831]  está el bloque de memoria 255 y esta en el bloque de cache 7, pues 255 mod 8 = 1
	public int calcularBloqueCache(int dirMem){
		int bloqueMem = calcularBloqueMemoria(dirMem);
		int posBloqueCache = bloqueMem % 8;
		return posBloqueCache;
	}
	
	//la memoria total es de 4096 enteros, 768 para instrucciones y 3328 para datos
	//como cada entero en la memoria de datos es de 4 bytes hay que hacer el calculo para accesar a la memoria de datos
	//así las direcciones referenciables son de 768 a 3324, siendo 768 v[0], 772 v[1], ... 3324 v[831]
	//debe verificarse que las direcciones de memoria sean ser multiplos de 4 y que esten entre 768 y 3324
	//retorna true si la direccion es valida, de lo contrario devuelve false
	public boolean verificarDirMem(int dir){
		boolean valida=false;
		//primero verifica si la direccion es valida
		if((dir>767 && dir<4093) && (dir % 4 == 0 )){
			valida = true;
		}
		return valida;
	}
	
	//NO confundir con instruccion LOAD
	//guarda un bloque en cache, traído desde memoria
	//recibe la posicion de memoria en la cual esta el dato que se quiere cargar
	public void cacheLoad(int dirMemoria){
		int bloqueMem = calcularBloqueMemoria(dirMemoria);
		int bloqueCache = calcularBloqueCache(dirMemoria);
		for(int i=0; i<4; ++i){//4 porque cada bloque contiene 16 enteros
			cache[bloqueCache].setBloquePos(i, dataMem[(bloqueMem-48)*4+i]);
		}
		cache[bloqueCache].setEtiqueta(bloqueMem);
	}//fin del metodo cacheLoad
	
	//NO confundir con instruccion STORE
	//guarda en memoria un bloque que está en la cache de datos
	//recibe el numero de bloque de de cache que se quiere guardar (que basta para saber el bloque en memoria)
	public void cacheStore(int numBloqueCache){
		int dirMem = cache[numBloqueCache].getEtiqueta()*4 - 192; //direccion a partir de la que hay que guardar
		for(int i=0; i<4; ++i){//4 porque cada bloque contiene 4 enteros
			instructionMem[dirMem+i] = cache[numBloqueCache].getValor(i);
		}
	}//fin del metodo cacheStore
	
	//metodo para saber si un bloque de memoria está guardado en la cache
	//recibe la direccion de memoria que se quiere accesar
	//devuelve true si está en cache, de lo contrario devuleve false
	public boolean hitMemoria(int dirMemoria){
		boolean hit = false;
		int bloqueMem = calcularBloqueMemoria(dirMemoria);
		int bloqueCache = calcularBloqueCache(dirMemoria);
		if(cache[bloqueCache].getEtiqueta() == bloqueMem){
			hit = true;
		}
		return hit;
	}//fin del metodo hitMemoria
	
	//imprime el estado de TODAS las variables del mips
	void imprimirEstado(){
		//imprime el estado de los registros
		System.out.format("-----------------REGISTROS-----------------\n");
		int count=0;
		for(int i=0; i<8; ++i){
			for(int j=0; j<4; ++j){
				System.out.format("R%02d" + ": " + "%04d, ", count, R[count]);

				++count;
			}
			System.out.println();
		}
		
		//imprime la memoria de instrucciones
		System.out.println("\n----MEMORIA DE INSTRUCCIONES----");
		count=0;
		for(int i=0; i<48; ++i){
			System.out.format("Bloque%02d: ", i);
			for(int j=0; j<4; ++j){
				System.out.format("%04d" + ", ", instructionMem[count]);
				++count;
			}
			System.out.println();
		}
		
		//imprime la memoria de datos
		System.out.println("\n---------MEMORIA DE DATOS---------");
		count=0;
		for(int i=48; i<256; ++i){
			System.out.format("Bloque%03d: ", i);
			for(int j=0; j<4; ++j){
				System.out.format("%04d" + ", ", dataMem[count]);
				++count;
			}
			System.out.println();
		}
		
		//imprime la memoria cache
		System.out.println("\n----------MEMORIA CACHÉ---------");
		count=0;
		for(int i=0; i<8; ++i){
			System.out.format("Bloque%02d: ", i);
			for(int j=0; j<4; ++j){
				System.out.format("%04d" + ", ", cache[i].getValor(j));
			}
			System.out.println();
		}
		
		//agregar imprimir middle stages, pc, IR, etc
		
	}//fin del metodo imprimirEstado
	
	public void fetch(){
			
			if(IR[0] == FIN){
				ifAlive = false;
			}
			
			for(int i = 0; i < 4; ++i){
				IR[i] = instructionMem[PC+i];
			}
			
			// Guarda la instrucción a ejecutar en IR
			// hasta que ID este desocupado se ejecuta

			for(int i = 0; i < 4; ++i){
				IF_ID[i] = IR[i];
			}
			
			//*****
			System.out.println("ENTRO: IF_ID: " + IF_ID[0] +
					IF_ID[1] + IF_ID[2] + IF_ID[3]);
			// Aumenta el PC
			PC += 4;
			// Espera a que ID se desocupe con un lock o algo así		
	}//fin metodo fetch
	
	public void decode(){

		//System.out.println("ENtro ID");
		
		switch(IF_ID[0]){//contiene el código de instruccion
		case DADDI:
			ID_EX[0] = R[IF_ID[1]]; 		// RY
			ID_EX[1] = IF_ID[2];          	// X
			ID_EX[2] = IF_ID[3];          	// n
			ID_EX[3] = IF_ID[0];			//operation code
		break;
		case DADD:
			ID_EX[0] = R[IF_ID[1]]; 		// Reg Operando1
			ID_EX[1] = R[IF_ID[2]]; 		// Reg Operando2
			ID_EX[2] = IF_ID[3];          	// Reg Destino
			ID_EX[3] = IF_ID[0];			//operation code
		break;
		case DSUB:
			ID_EX[0] = R[IF_ID[1]]; 		// Reg Operando1
			ID_EX[1] = R[IF_ID[2]]; 		// Reg Operando2
			ID_EX[2] = IF_ID[3];          	// Reg Destino
			ID_EX[3] = IF_ID[0];			//operation code
		break;
		case DMUL:
			ID_EX[0] = R[IF_ID[1]]; 		// Reg Operando1
			ID_EX[1] = R[IF_ID[2]]; 		// Reg Operando2
			ID_EX[2] = IF_ID[3];          	// Reg Operando3
			ID_EX[3] = IF_ID[0];			//operation code
		break;
		case DDIV:
			ID_EX[0] = R[IF_ID[1]];			// Reg Operando1
			ID_EX[1] = R[IF_ID[2]];			// Reg Operando2
			ID_EX[2] = IF_ID[3];			// Reg Operando3
			ID_EX[3] = IF_ID[0];			//operation code
		break;
		case LW:
			ID_EX[0] = IF_ID[3]; 			// valor inmediato
			ID_EX[1] = R[IF_ID[1]];  		// Origen
			ID_EX[2] = IF_ID[2];          	// Destino
			ID_EX[3] = IF_ID[0];			//operation code
		break;
		case SW:
			ID_EX[0] = IF_ID[3]; 			// valor inmediato
			ID_EX[1] = R[IF_ID[2]]; 		// Origen
			ID_EX[2] = R[IF_ID[1]];			// Destino
			ID_EX[3] = IF_ID[0];			//operation code
		break;
		case LL:
			
		break;
		case SC:
			
		break;
		
	  }//fin del switch

		//System.out.println("ENTRO: ID_EX: " + ID_EX[0] + ID_EX[1] + ID_EX[2] + ID_EX[3]);
		
	}//fin metodo decode
	
	public void execute(){
		switch(ID_EX[3]){
		case DADDI:
			EX_MEM[0] = ID_EX[0]+ID_EX[2];
			EX_MEM[1] = ID_EX[1]; //como escribe en registro, el campo de memoria va vacio
			EX_MEM[2] = ID_EX[3]; //codigo de operacion
		break;
		case DADD:
			EX_MEM[0] = ID_EX[0] + ID_EX[1];
			EX_MEM[1] = ID_EX[2];
			EX_MEM[2] = ID_EX[3]; //codigo de operacion
		break;
		case DSUB:
			EX_MEM[0] = ID_EX[0] - ID_EX[1];
			EX_MEM[1] = ID_EX[2];
			EX_MEM[2] = ID_EX[3]; //codigo de operacion
		break;
		case DMUL:
			EX_MEM[0] = ID_EX[0] * ID_EX[1];
			EX_MEM[1] = ID_EX[2];
			EX_MEM[2] = ID_EX[3]; //codigo de operacion
		break;
		case DDIV:
			EX_MEM[0] = ID_EX[0] / ID_EX[1];
			EX_MEM[1] = ID_EX[2];
			EX_MEM[2] = ID_EX[3]; //codigo de operacion
		break;
		case LW:
			EX_MEM[0] = ID_EX[0]+ID_EX[1];	//Resultado de memoria del ALU
			EX_MEM[1] = ID_EX[2];			//Destino
			EX_MEM[2] = ID_EX[3]; 			//codigo de operacion
		break;
		case SW:
			EX_MEM[0] = ID_EX[0]+ID_EX[2];	//Resultado de memoria del ALU
			EX_MEM[1] = ID_EX[1];			//Destino
			EX_MEM[2] = ID_EX[3]; 			//codigo de operacion
		break;
	}//fin del switch
	}//fin metodo execute
	
	public void memory(){
		boolean direccionValida;
		int bloqueMem, bloqueCache;
		switch(EX_MEM[2]){
			case LW:
				/*codigo viejo, sin implementacion de cache
				MEM_WB[0] = dataMem[EX_MEM[0]]; //
				MEM_WB[1] = EX_MEM[1];			//
				MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion */
				
				//primero hay que verificar que la direccion que se tratará de leer sea valida
				direccionValida = verificarDirMem(EX_MEM[0]);
				if(direccionValida){//si diera false, sería bueno agregar un manejo de excepcion
					//si fuera válida, hay que verificar que haya un hit de memoria en cache.
					//si diera fallo, hay que traer el bloque desde memoria, si estuviera se toma directo de la cache
					bloqueMem = calcularBloqueMemoria(EX_MEM[0]);
					bloqueCache = calcularBloqueCache(EX_MEM[0]);
					if(!hitMemoria(EX_MEM[0])){
						//si hubiera fallo de memoria y el bloque en cache correspondiente está modificado,
						//primero hay que guardarlo a memoria. para esto primero calculamos el bloque en memoria
						//del dato que se quiere cargar. es aqui cuando se usa la estrategia WRITE ALLOCATE
						if(cache[bloqueCache].getEtiqueta() == bloqueMem && cache[bloqueCache].getEtiqueta() == 'm'){
							cacheStore(bloqueCache);
						}
						//en este punto se hace se carga la cache, pues no hubo hit de memoria
						//y si el bloque estaba modificado ya se guardó antes a memoria
						//es aqui cuando se usa la estrategia de WRITE BACK
						cacheLoad(EX_MEM[0]);
					}
					//en este punto sí hubo hit de memoria, por lo que se carga el dato desde la cache
					int indice = ((EX_MEM[0]+768) / 16 ) % 4;
					MEM_WB[0] = cache[bloqueCache].getValor(indice);
					//System.out.println(MEM_WB[0]);
					MEM_WB[1] = EX_MEM[1];			//
					MEM_WB[2] = EX_MEM[2]; 			//en MEM_WB[2] va el codigo de operacion			
				}//fin de if direccionValida
			break;
			
			case SW:
				/*codigo viejo, sin implementacion de cache
				dataMem[EX_MEM[0]] = EX_MEM[1]; //se hace el store en memoria
				MEM_WB[0] = EX_MEM[0];			//no hace nada pero tiene que pasar el valor
				MEM_WB[1] = EX_MEM[1];			//no hace nada pero tiene que pasar el valor
				MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion*/
				
				//nuevamente, lo primero es verificar que la referencia a memoria sea valida
				direccionValida = verificarDirMem(EX_MEM[0]);
				if(direccionValida){
					//si fuera valida, se verifica si el bloque de memoria está en cache
						bloqueMem = calcularBloqueMemoria(EX_MEM[0]);
						bloqueCache = calcularBloqueCache(EX_MEM[0]);
						if(!hitMemoria(EX_MEM[0])){
							//si no hay hit de memoria hay que cargar el bloque a cache, pero si en el bloque de cache
							//donde vamos a escribir está modificado, primero hay que escribir el bloque actual a memoria:
							if(cache[bloqueCache].getEtiqueta() == bloqueMem && cache[bloqueCache].getEtiqueta() == 'm'){
								cacheStore(bloqueCache);
							}
							//si no estuviera modificado, entonces ya podemos escribir el bloque de cache a memoria
							cacheLoad(EX_MEM[0]);
						}
						//con el bloque ya en cache, debemos escribir en la posicion correcta del bloque
						int offset = (EX_MEM[0] / 4) % 4; //se calcula el desplazamiento en el bloque
						cache[bloqueCache].setBloquePos(offset, EX_MEM[1]); //el valor a guardar esta en EX_MEM[1]
						cache[bloqueCache].setEstado('m'); //cuando se escribe en cache el estado debe pasar a modificado
						
						//pasa los valores a MEM_WB
						MEM_WB[0] = EX_MEM[0];			//no hace nada pero tiene que pasar el valor
						MEM_WB[1] = EX_MEM[1];			//no hace nada pero tiene que pasar el valor
						MEM_WB[2] = EX_MEM[2]; 			//en MEM_WB[2] va el codigo de operacion
				}
			break;
			
			default:
				MEM_WB[0] = EX_MEM[0];
				MEM_WB[1] = EX_MEM[1];
				MEM_WB[2] = EX_MEM[2]; 			//en MEM_WB va el codigo de operacion
		}//fin del switch
	}//fin metodo memory
	
	public void writeBack(){
		switch(MEM_WB[2]){ //codigo de operacion en MEM_WB[2]
		case DADDI:
			R[MEM_WB[1]] = MEM_WB[0];
		break;
		case DADD:
			R[MEM_WB[1]] = MEM_WB[0];
		break;
		case DSUB:
			R[MEM_WB[1]] = MEM_WB[0];
		break;
		case DMUL:
			R[MEM_WB[1]] = MEM_WB[0];
		break;
		case DDIV:
			R[MEM_WB[1]] = MEM_WB[0];
		break;
		case LW:
			R[MEM_WB[1]] = MEM_WB[0];
		break;
		//en RW no hace nada en la etapa de writeback
		}//fin del switch
	}//fin metodo writeBack
	
}//fin de la clase
