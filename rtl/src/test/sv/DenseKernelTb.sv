// A self-checking testbench for the generated DenseKernel, run under Verilator.
//
// Everything else here is verified by interpreting the elaborated design on the JVM, which cannot
// say whether the *generated Verilog* agrees with it. This can: it reads the same golden vectors
// Scotty produced, drives the netlist that would go to synthesis or an FPGA, and compares bit for
// bit. The expected values are computed with the kernel's own fixed-point arithmetic, so there is
// no tolerance here and none is wanted.
//
// It drives Verilator directly rather than through a Chisel harness because, as of Chisel 6.7.0,
// neither chiseltest's Verilator backend nor ChiselSim's svsim works with Verilator 5.052 — the
// first fails to compile its harness with "unknown type name 'WData'", the second desynchronises
// with "Unexpected message: Ready" on the first clock edge.

module DenseKernelTb;
  localparam int WIDTH      = 3;
  localparam int DIMENSION  = 1 << WIDTH;
  localparam int DATA_WIDTH = 24;

  logic clock = 0;
  logic reset = 1;

  logic                         writeEnable;
  logic [2*WIDTH-1:0]           writeAddr;
  logic signed [DATA_WIDTH-1:0] writeRe, writeIm;
  logic                         inValid;
  logic [WIDTH-1:0]             row;
  logic signed [DATA_WIDTH-1:0] inRe  [DIMENSION];
  logic signed [DATA_WIDTH-1:0] inIm  [DIMENSION];
  logic                         outValid;
  logic signed [DATA_WIDTH-1:0] outRe, outIm;

  // Golden vectors, as emitted by brokkr.vectors.DenseVectors.
  logic [DATA_WIDTH-1:0] operator [2*DIMENSION*DIMENSION];
  logic [DATA_WIDTH-1:0] stimulus [2*DIMENSION];
  logic [DATA_WIDTH-1:0] expected [2*DIMENSION];

  int failures = 0;

  DenseKernel dut (
    .clock(clock), .reset(reset),
    .io_writeEnable(writeEnable), .io_writeAddr(writeAddr),
    .io_writeRe(writeRe), .io_writeIm(writeIm),
    .io_inValid(inValid), .io_row(row),
    .io_inRe_0(inRe[0]), .io_inRe_1(inRe[1]), .io_inRe_2(inRe[2]), .io_inRe_3(inRe[3]),
    .io_inRe_4(inRe[4]), .io_inRe_5(inRe[5]), .io_inRe_6(inRe[6]), .io_inRe_7(inRe[7]),
    .io_inIm_0(inIm[0]), .io_inIm_1(inIm[1]), .io_inIm_2(inIm[2]), .io_inIm_3(inIm[3]),
    .io_inIm_4(inIm[4]), .io_inIm_5(inIm[5]), .io_inIm_6(inIm[6]), .io_inIm_7(inIm[7]),
    .io_outValid(outValid), .io_outRe(outRe), .io_outIm(outIm)
  );

  always #5 clock = ~clock;

  task automatic tick();
    @(posedge clock);
    #1;
  endtask

  initial begin
    $readmemh("rtl/src/test/resources/dense-operator.hex", operator);
    $readmemh("rtl/src/test/resources/dense-input.hex", stimulus);
    $readmemh("rtl/src/test/resources/dense-expected.hex", expected);

    writeEnable = 0;
    inValid     = 0;
    row         = 0;

    tick();
    reset = 0;
    tick();

    // Load the operator, row-major, one entry per cycle.
    for (int entry = 0; entry < DIMENSION * DIMENSION; entry++) begin
      writeEnable = 1;
      writeAddr   = entry[2*WIDTH-1:0];
      writeRe     = operator[2*entry];
      writeIm     = operator[2*entry + 1];
      tick();
    end

    writeEnable = 0;

    for (int i = 0; i < DIMENSION; i++) begin
      inRe[i] = stimulus[2*i];
      inIm[i] = stimulus[2*i + 1];
    end

    // One output row per cycle. The kernel registers its result, so compare one tick later.
    for (int r = 0; r < DIMENSION; r++) begin
      inValid = 1;
      row     = r[WIDTH-1:0];
      tick();

      if (outRe !== $signed(expected[2*r])) begin
        $display("FAIL row %0d re: got %0d want %0d", r, outRe, $signed(expected[2*r]));
        failures++;
      end
      if (outIm !== $signed(expected[2*r + 1])) begin
        $display("FAIL row %0d im: got %0d want %0d", r, outIm, $signed(expected[2*r + 1]));
        failures++;
      end
      if (!outValid) begin
        $display("FAIL row %0d: outValid low", r);
        failures++;
      end
    end

    if (failures == 0)
      $display("PASS DenseKernel: %0d rows match Scotty's vectors bit for bit", DIMENSION);
    else
      $display("FAILED with %0d mismatches", failures);

    $finish;
  end
endmodule
